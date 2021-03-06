/**
 * Copyright (c) 2017 by Cisco Systems, Inc. All Rights Reserved Cisco Systems Confidential
 */
package com.cisco.iot.swp.batch.controller;


import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cisco.iot.swp.batch.exception.IoTBatchingErrorCode;
import com.cisco.iot.swp.batch.exception.IoTBatchingException;
import com.cisco.iot.swp.device.sdk.common.compression.ICompressionUtils;
import com.cisco.iot.swp.device.sdk.common.model.message.type.BatchMessage;
import com.cisco.iot.swp.device.sdk.common.utils.BaseConstantsUserParams;
import com.cisco.iot.swp.edge.mqtt.client.ICloudConnectClient;

public class BatchToCloudClient {


  private static IBatchStoreManager batchManager = null;
  private ICompressionUtils compressionUtils = null;
  private static Logger LOG = LoggerFactory.getLogger(BatchToCloudClient.class);
  private byte[] batch;
  private ScheduledExecutorService checkBatchReadyThread;


  public BatchToCloudClient(Properties props, ICompressionUtils compression,
      ICloudConnectClient dcClient) throws IoTBatchingException {
    if (props == null) {
      throw new IoTBatchingException(IoTBatchingErrorCode.MISSING_PROPERTIES,
          "Properties recieved by BatchToCloudClient is null.");
    }
    if (compression == null) {
      throw new IoTBatchingException(IoTBatchingErrorCode.COMPRESSION_OBJECT_MISSING,
          "Compression object is null in constructor of BatchStoreManager");
    }
    if (dcClient == null) {
      LOG.info("No valid MQTT Client found, logging messages to output");
    }
    this.compressionUtils = compression;
    batchManager = new BatchStoreManager(props, this.compressionUtils);
    String topic = props.getProperty(BaseConstantsUserParams.GATEWAY_OBV_BATCH_TOPIC);
    checkBatchReady(topic, dcClient);
  }

  protected void checkBatchReady(String topic, ICloudConnectClient dcClient) {
    LOG.debug("Starting new thread to check batch readiness");
    checkBatchReadyThread = Executors.newScheduledThreadPool(1);

    checkBatchReadyThread.scheduleAtFixedRate(new Runnable() {
      public void run() {
        batch = batchManager.getCompressedBatch();
        if (batch != null && batch.length != 0) {
          sendBatch(topic, dcClient, batch);
          LOG.trace("batch {} sent to broker or published to file", batch);
        }
      }
    }, 1, 3, TimeUnit.SECONDS);
    /// checkBatchReadyThread.shutdown();
  }

  public boolean putMessageInBatch(BatchMessage message) throws IoTBatchingException {
    if (message != null) {
      String messageStr = message.toString();
      if (batchManager.addMessageToBatch(messageStr) == true) {
        return true;
      } else {
        LOG.error("err='Message {} was not properly added to the batch'", messageStr);
        return false;
      }
    }
    return false;
  }

  protected void sendBatch(String topic, ICloudConnectClient dcClient, byte[] batch) {
    if (dcClient != null) {
      try {
        synchronized (dcClient) {
          dcClient.publish(topic, batch);
        }
        LOG.debug("msg='Successfully published byte array to MQTT'");
      } catch (Exception e) {
        LOG.error("err='Message failed to send immediately', errMessage={}, errStack={}",
            e.getMessage(), e.getStackTrace());
        batchManager.handleUnsentCompressedBatch(batch);
        LOG.debug("msg='Compressed batch handled by batch manager to be sent later'");
      }
    } else {
      LOG.trace("No MQTT Client detected, logging to output");
      try {
        byte[] decompressedBatch = compressionUtils.decompress(batch);
        String data = new String(decompressedBatch);
        LOG.debug("msg='Decompressed string:{}'", new String(data));
      } catch (DataFormatException | IOException e) {
        LOG.error("err='Decompression of batch failed with error {}', errMessage={}, errStack={}",
            e.getMessage(), e.getStackTrace());
        batchManager.handleUnsentCompressedBatch(batch);
        LOG.debug("msg='Compressed batch handled by batch manager to be sent later'");
      }
    }
  }

  public void closeBatchCheckThread() {
    LOG.info("msg='Closing Batch Checker Thread'");
    batchManager.closeBatchManager();
    checkBatchReadyThread.shutdown();
  }
}