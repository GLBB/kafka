/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package kafka.server.epoch

import java.io.File
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

import kafka.log.{Log, LogManager}
import kafka.server._
import kafka.utils.{MockTime, TestUtils}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{EpochEndOffset, OffsetsForLeaderEpochRequest}
import org.apache.kafka.common.requests.EpochEndOffset._
import org.easymock.EasyMock._
import org.junit.Assert._
import org.junit.Test

class OffsetsForLeaderEpochTest {
  private val config = TestUtils.createBrokerConfigs(1, TestUtils.MockZkConnect).map(KafkaConfig.fromProps).head
  private val time = new MockTime
  private val metrics = new Metrics
  private val alterIsrManager = TestUtils.createAlterIsrManager()
  private val tp = new TopicPartition("topic", 1)

  @Test
  def shouldGetEpochsFromReplica(): Unit = {
    //Given
    val offsetAndEpoch = OffsetAndEpoch(42L, 5)
    val epochRequested: Integer = 5
    val request = Map(tp -> new OffsetsForLeaderEpochRequest.PartitionData(Optional.empty(), epochRequested))

    //Stubs
    val mockLog: Log = createNiceMock(classOf[Log])
    val logManager: LogManager = createNiceMock(classOf[LogManager])
    expect(mockLog.endOffsetForEpoch(epochRequested)).andReturn(Some(offsetAndEpoch))
    expect(logManager.liveLogDirs).andReturn(Array.empty[File]).anyTimes()
    replay(mockLog, logManager)

    // create a replica manager with 1 partition that has 1 replica
    val replicaManager = new ReplicaManager(config, metrics, time, null, null, logManager, new AtomicBoolean(false),
      QuotaFactory.instantiate(config, metrics, time, ""), new BrokerTopicStats,
      new MetadataCache(config.brokerId), new LogDirFailureChannel(config.logDirs.size), alterIsrManager)
    val partition = replicaManager.createPartition(tp)
    partition.setLog(mockLog, isFutureLog = false)
    partition.leaderReplicaIdOpt = Some(config.brokerId)

    //When
    val response = replicaManager.lastOffsetForLeaderEpoch(request)

    //Then
    assertEquals(new EpochEndOffset(Errors.NONE, offsetAndEpoch.leaderEpoch, offsetAndEpoch.offset), response(tp))
  }

  @Test
  def shouldReturnNoLeaderForPartitionIfThrown(): Unit = {
    val logManager: LogManager = createNiceMock(classOf[LogManager])
    expect(logManager.liveLogDirs).andReturn(Array.empty[File]).anyTimes()
    replay(logManager)

    //create a replica manager with 1 partition that has 0 replica
    val replicaManager = new ReplicaManager(config, metrics, time, null, null, logManager, new AtomicBoolean(false),
      QuotaFactory.instantiate(config, metrics, time, ""), new BrokerTopicStats,
      new MetadataCache(config.brokerId), new LogDirFailureChannel(config.logDirs.size), alterIsrManager)
    replicaManager.createPartition(tp)

    //Given
    val epochRequested: Integer = 5
    val request = Map(tp -> new OffsetsForLeaderEpochRequest.PartitionData(Optional.empty(), epochRequested))

    //When
    val response = replicaManager.lastOffsetForLeaderEpoch(request)

    //Then
    assertEquals(new EpochEndOffset(Errors.NOT_LEADER_OR_FOLLOWER, UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET), response(tp))
  }

  @Test
  def shouldReturnUnknownTopicOrPartitionIfThrown(): Unit = {
    val logManager: LogManager = createNiceMock(classOf[LogManager])
    expect(logManager.liveLogDirs).andReturn(Array.empty[File]).anyTimes()
    replay(logManager)

    //create a replica manager with 0 partition
    val replicaManager = new ReplicaManager(config, metrics, time, null, null, logManager, new AtomicBoolean(false),
      QuotaFactory.instantiate(config, metrics, time, ""), new BrokerTopicStats,
      new MetadataCache(config.brokerId), new LogDirFailureChannel(config.logDirs.size), alterIsrManager)

    //Given
    val epochRequested: Integer = 5
    val request = Map(tp -> new OffsetsForLeaderEpochRequest.PartitionData(Optional.empty(), epochRequested))

    //When
    val response = replicaManager.lastOffsetForLeaderEpoch(request)

    //Then
    assertEquals(new EpochEndOffset(Errors.UNKNOWN_TOPIC_OR_PARTITION, UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET), response(tp))
  }
}
