/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.service.deploy.worker

import java.io.IOException
import java.util.{ArrayList => jArrayList, HashMap => jHashMap, List => jList, Set => jSet}
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.function.BiFunction

import scala.collection.JavaConverters._

import io.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import org.roaringbitmap.RoaringBitmap

import org.apache.celeborn.common.CelebornConf
import org.apache.celeborn.common.identity.UserIdentifier
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.meta.{PartitionLocationInfo, WorkerInfo}
import org.apache.celeborn.common.metrics.MetricsSystem
import org.apache.celeborn.common.protocol.{PartitionLocation, PartitionSplitMode, PartitionType, StorageInfo}
import org.apache.celeborn.common.protocol.message.ControlMessages._
import org.apache.celeborn.common.protocol.message.StatusCode
import org.apache.celeborn.common.rpc._
import org.apache.celeborn.common.util.Utils
import org.apache.celeborn.service.deploy.worker.storage.StorageManager

private[deploy] class Controller(
    override val rpcEnv: RpcEnv,
    val conf: CelebornConf,
    val metricsSystem: MetricsSystem)
  extends RpcEndpoint with Logging {

  var workerSource: WorkerSource = _
  var storageManager: StorageManager = _
  var shuffleMapperAttempts: ConcurrentHashMap[String, Array[Int]] = _
  var workerInfo: WorkerInfo = _
  var partitionLocationInfo: PartitionLocationInfo = _
  var timer: HashedWheelTimer = _
  var commitThreadPool: ThreadPoolExecutor = _
  var asyncReplyPool: ScheduledExecutorService = _
  val minPartitionSizeToEstimate = conf.minPartitionSizeToEstimate
  var shutdown: AtomicBoolean = _

  def init(worker: Worker): Unit = {
    workerSource = worker.workerSource
    storageManager = worker.storageManager
    shuffleMapperAttempts = worker.shuffleMapperAttempts
    workerInfo = worker.workerInfo
    partitionLocationInfo = worker.partitionLocationInfo
    timer = worker.timer
    commitThreadPool = worker.commitThreadPool
    asyncReplyPool = worker.asyncReplyPool
    shutdown = worker.shutdown
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case ReserveSlots(
          applicationId,
          shuffleId,
          masterLocations,
          slaveLocations,
          splitThreshold,
          splitMode,
          partitionType,
          rangeReadFilter,
          userIdentifier) =>
      val shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId)
      workerSource.sample(WorkerSource.ReserveSlotsTime, shuffleKey) {
        logDebug(s"Received ReserveSlots request, $shuffleKey, " +
          s"master partitions: ${masterLocations.asScala.map(_.getUniqueId).mkString(",")}; " +
          s"slave partitions: ${slaveLocations.asScala.map(_.getUniqueId).mkString(",")}.")
        handleReserveSlots(
          context,
          applicationId,
          shuffleId,
          masterLocations,
          slaveLocations,
          splitThreshold,
          splitMode,
          partitionType,
          rangeReadFilter,
          userIdentifier)
        logDebug(s"ReserveSlots for $shuffleKey finished.")
      }

    case CommitFiles(applicationId, shuffleId, masterIds, slaveIds, mapAttempts) =>
      val shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId)
      workerSource.sample(WorkerSource.CommitFilesTime, shuffleKey) {
        logDebug(s"Received CommitFiles request, $shuffleKey, master files" +
          s" ${masterIds.asScala.mkString(",")}; slave files ${slaveIds.asScala.mkString(",")}.")
        val commitFilesTimeMs = Utils.timeIt({
          handleCommitFiles(context, shuffleKey, masterIds, slaveIds, mapAttempts)
        })
        logDebug(s"Done processed CommitFiles request with shuffleKey $shuffleKey, in " +
          s"$commitFilesTimeMs ms.")
      }

    case GetWorkerInfos =>
      handleGetWorkerInfos(context)

    case ThreadDump =>
      handleThreadDump(context)

    case Destroy(shuffleKey, masterLocations, slaveLocations) =>
      handleDestroy(context, shuffleKey, masterLocations, slaveLocations)
  }

  private def handleReserveSlots(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      requestMasterLocs: jList[PartitionLocation],
      requestSlaveLocs: jList[PartitionLocation],
      splitThreshold: Long,
      splitMode: PartitionSplitMode,
      partitionType: PartitionType,
      rangeReadFileter: Boolean,
      userIdentifier: UserIdentifier): Unit = {
    val shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId)
    if (shutdown.get()) {
      val msg = "Current worker is shutting down!"
      logError(s"[handleReserveSlots] $msg")
      context.reply(ReserveSlotsResponse(StatusCode.RESERVE_SLOTS_FAILED, msg))
      return
    }

    if (storageManager.healthyWorkingDirs().size <= 0) {
      val msg = "Local storage has no available dirs!"
      logError(s"[handleReserveSlots] $msg")
      context.reply(ReserveSlotsResponse(StatusCode.RESERVE_SLOTS_FAILED, msg))
      return
    }
    val masterLocs = new jArrayList[PartitionLocation]()
    try {
      for (ind <- 0 until requestMasterLocs.size()) {
        var location = partitionLocationInfo.getMasterLocation(
          shuffleKey,
          requestMasterLocs.get(ind).getUniqueId)
        if (location == null) {
          location = requestMasterLocs.get(ind)
          val writer = storageManager.createWriter(
            applicationId,
            shuffleId,
            location,
            splitThreshold,
            splitMode,
            partitionType,
            rangeReadFileter,
            userIdentifier)
          masterLocs.add(new WorkingPartition(location, writer))
        } else {
          masterLocs.add(location)
        }
      }
    } catch {
      case e: Exception =>
        logError(s"CreateWriter for $shuffleKey failed.", e)
    }
    if (masterLocs.size() < requestMasterLocs.size()) {
      val msg = s"Not all master partition satisfied for $shuffleKey"
      logWarning(s"[handleReserveSlots] $msg, will destroy writers.")
      masterLocs.asScala.foreach(_.asInstanceOf[WorkingPartition].getFileWriter.destroy())
      context.reply(ReserveSlotsResponse(StatusCode.RESERVE_SLOTS_FAILED, msg))
      return
    }

    val slaveLocs = new jArrayList[PartitionLocation]()
    try {
      for (ind <- 0 until requestSlaveLocs.size()) {
        var location =
          partitionLocationInfo.getSlaveLocation(
            shuffleKey,
            requestSlaveLocs.get(ind).getUniqueId)
        if (location == null) {
          location = requestSlaveLocs.get(ind)
          val writer = storageManager.createWriter(
            applicationId,
            shuffleId,
            location,
            splitThreshold,
            splitMode,
            partitionType,
            rangeReadFileter,
            userIdentifier)
          slaveLocs.add(new WorkingPartition(location, writer))
        } else {
          slaveLocs.add(location)
        }
      }
    } catch {
      case e: Exception =>
        logError(s"CreateWriter for $shuffleKey failed.", e)
    }
    if (slaveLocs.size() < requestSlaveLocs.size()) {
      val msg = s"Not all slave partition satisfied for $shuffleKey"
      logWarning(s"[handleReserveSlots] $msg, destroy writers.")
      masterLocs.asScala.foreach(_.asInstanceOf[WorkingPartition].getFileWriter.destroy())
      slaveLocs.asScala.foreach(_.asInstanceOf[WorkingPartition].getFileWriter.destroy())
      context.reply(ReserveSlotsResponse(StatusCode.RESERVE_SLOTS_FAILED, msg))
      return
    }

    // reserve success, update status
    partitionLocationInfo.addMasterPartitions(shuffleKey, masterLocs)
    partitionLocationInfo.addSlavePartitions(shuffleKey, slaveLocs)

    workerInfo.allocateSlots(shuffleKey, Utils.getSlotsPerDisk(requestMasterLocs, requestSlaveLocs))

    logInfo(s"Reserved ${masterLocs.size()} master location" +
      s" and ${slaveLocs.size()} slave location for $shuffleKey ")
    if (log.isDebugEnabled()) {
      logDebug(s"master: $masterLocs\nslave: $slaveLocs.")
    }
    context.reply(ReserveSlotsResponse(StatusCode.SUCCESS))
  }

  private def commitFiles(
      shuffleKey: String,
      uniqueIds: jList[String],
      committedIds: jSet[String],
      failedIds: jSet[String],
      committedStorageInfos: ConcurrentHashMap[String, StorageInfo],
      committedMapIdBitMap: ConcurrentHashMap[String, RoaringBitmap],
      partitionSizeList: LinkedBlockingQueue[Long],
      master: Boolean = true): CompletableFuture[Void] = {
    var future: CompletableFuture[Void] = null

    if (uniqueIds != null) {
      uniqueIds.asScala.foreach { uniqueId =>
        val task = CompletableFuture.runAsync(
          new Runnable {
            override def run(): Unit = {
              try {
                val location =
                  if (master) {
                    partitionLocationInfo.getMasterLocation(shuffleKey, uniqueId)
                  } else {
                    partitionLocationInfo.getSlaveLocation(shuffleKey, uniqueId)
                  }

                if (location == null) {
                  logWarning(s"Get Partition Location for $shuffleKey $uniqueId but didn't exist.")
                  return
                }

                val fileWriter = location.asInstanceOf[WorkingPartition].getFileWriter
                val bytes = fileWriter.close()
                if (bytes > 0L) {
                  if (fileWriter.getStorageInfo != null) {
                    committedStorageInfos.put(uniqueId, fileWriter.getStorageInfo)
                    if (fileWriter.getMapIdBitMap != null) {
                      committedMapIdBitMap.put(uniqueId, fileWriter.getMapIdBitMap)
                    }
                  }
                  if (bytes >= minPartitionSizeToEstimate) {
                    partitionSizeList.add(bytes)
                  }
                  committedIds.add(uniqueId)
                }
              } catch {
                case e: IOException =>
                  logError(s"Commit file for $shuffleKey $uniqueId failed.", e)
                  failedIds.add(uniqueId)
              }
            }
          },
          commitThreadPool)

        if (future == null) {
          future = task
        } else {
          future = CompletableFuture.allOf(future, task)
        }
      }
    }

    future
  }

  private def handleCommitFiles(
      context: RpcCallContext,
      shuffleKey: String,
      masterIds: jList[String],
      slaveIds: jList[String],
      mapAttempts: Array[Int]): Unit = {
    // return null if shuffleKey does not exist
    if (!partitionLocationInfo.containsShuffle(shuffleKey)) {
      logError(s"Shuffle $shuffleKey doesn't exist!")
      context.reply(
        CommitFilesResponse(
          StatusCode.SHUFFLE_NOT_REGISTERED,
          List.empty.asJava,
          List.empty.asJava,
          masterIds,
          slaveIds))
      return
    }

    // close and flush files.
    shuffleMapperAttempts.putIfAbsent(shuffleKey, mapAttempts)

    // Use ConcurrentSet to avoid excessive lock contention.
    val committedMasterIds = ConcurrentHashMap.newKeySet[String]()
    val committedSlaveIds = ConcurrentHashMap.newKeySet[String]()
    val failedMasterIds = ConcurrentHashMap.newKeySet[String]()
    val failedSlaveIds = ConcurrentHashMap.newKeySet[String]()
    val committedMasterStorageInfos = new ConcurrentHashMap[String, StorageInfo]()
    val committedSlaveStorageInfos = new ConcurrentHashMap[String, StorageInfo]()
    val committedMapIdBitMap = new ConcurrentHashMap[String, RoaringBitmap]()
    val partitionSizeList = new LinkedBlockingQueue[Long]()

    val masterFuture =
      commitFiles(
        shuffleKey,
        masterIds,
        committedMasterIds,
        failedMasterIds,
        committedMasterStorageInfos,
        committedMapIdBitMap,
        partitionSizeList)
    val slaveFuture = commitFiles(
      shuffleKey,
      slaveIds,
      committedSlaveIds,
      failedSlaveIds,
      committedSlaveStorageInfos,
      committedMapIdBitMap,
      partitionSizeList,
      false)

    val future =
      if (masterFuture != null && slaveFuture != null) {
        CompletableFuture.allOf(masterFuture, slaveFuture)
      } else if (masterFuture != null) {
        masterFuture
      } else if (slaveFuture != null) {
        slaveFuture
      } else {
        null
      }

    def reply(): Unit = {
      // release slots before reply.
      val releaseMasterLocations =
        partitionLocationInfo.removeMasterPartitions(shuffleKey, masterIds)
      val releaseSlaveLocations = partitionLocationInfo.removeSlavePartitions(shuffleKey, slaveIds)
      logDebug(s"$shuffleKey remove" +
        s" slots count ${releaseMasterLocations._2 + releaseSlaveLocations._2}")
      logDebug(s"CommitFiles result" +
        s" $committedMasterStorageInfos $committedSlaveStorageInfos")
      workerInfo.releaseSlots(shuffleKey, releaseMasterLocations._1)
      workerInfo.releaseSlots(shuffleKey, releaseSlaveLocations._1)

      val committedMasterIdList = new jArrayList[String](committedMasterIds)
      val committedSlaveIdList = new jArrayList[String](committedSlaveIds)
      val failedMasterIdList = new jArrayList[String](failedMasterIds)
      val failedSlaveIdList = new jArrayList[String](failedSlaveIds)
      val committedMasterStorageAndDiskHintList =
        new jHashMap[String, StorageInfo](committedMasterStorageInfos)
      val committedSlaveStorageAndDiskHintList =
        new jHashMap[String, StorageInfo](committedSlaveStorageInfos)
      val committedMapIdBitMapList = new jHashMap[String, RoaringBitmap](committedMapIdBitMap)
      val totalSize = partitionSizeList.asScala.sum
      val fileCount = partitionSizeList.size()
      // reply
      if (failedMasterIds.isEmpty && failedSlaveIds.isEmpty) {
        logInfo(s"CommitFiles for $shuffleKey success with ${committedMasterIds.size()}" +
          s" master partitions and ${committedSlaveIds.size()} slave partitions!")
        context.reply(
          CommitFilesResponse(
            StatusCode.SUCCESS,
            committedMasterIdList,
            committedSlaveIdList,
            List.empty.asJava,
            List.empty.asJava,
            committedMasterStorageAndDiskHintList,
            committedSlaveStorageAndDiskHintList,
            committedMapIdBitMapList,
            totalSize,
            fileCount))
      } else {
        logWarning(s"CommitFiles for $shuffleKey failed with ${failedMasterIds.size()} master" +
          s" partitions and ${failedSlaveIds.size()} slave partitions!")
        context.reply(
          CommitFilesResponse(
            StatusCode.PARTIAL_SUCCESS,
            committedMasterIdList,
            committedSlaveIdList,
            failedMasterIdList,
            failedSlaveIdList,
            committedMasterStorageAndDiskHintList,
            committedSlaveStorageAndDiskHintList,
            committedMapIdBitMapList,
            totalSize,
            fileCount))
      }
    }

    if (future != null) {
      val result = new AtomicReference[CompletableFuture[Unit]]()
      val shuffleCommitTimeout = conf.workerShuffleCommitTimeout

      val timeout = timer.newTimeout(
        new TimerTask {
          override def run(timeout: Timeout): Unit = {
            if (result.get() != null) {
              result.get().cancel(true)
              logWarning(s"After waiting $shuffleCommitTimeout s, cancel all commit file jobs.")
            }
          }
        },
        shuffleCommitTimeout,
        TimeUnit.SECONDS)

      result.set(future.handleAsync(
        new BiFunction[Void, Throwable, Unit] {
          override def apply(v: Void, t: Throwable): Unit = {
            if (null != t) {
              t match {
                case _: CancellationException =>
                  logWarning("While handling commitFiles, canceled.")
                case ee: ExecutionException =>
                  logError("While handling commitFiles, ExecutionException raised.", ee)
                case ie: InterruptedException =>
                  logWarning("While handling commitFiles, interrupted.")
                  Thread.currentThread().interrupt()
                  throw ie
                case _: TimeoutException =>
                  logWarning(s"While handling commitFiles, timeout after $shuffleCommitTimeout s.")
                case throwable: Throwable =>
                  logError("While handling commitFiles, exception occurs.", throwable)
              }
            } else {
              // finish, cancel timeout job first.
              timeout.cancel()
              reply()
            }
          }
        },
        asyncReplyPool
      )) // should not use commitThreadPool in case of block by commit files.
    } else {
      // If both of two futures are null, then reply directly.
      reply()
    }
  }

  private def handleDestroy(
      context: RpcCallContext,
      shuffleKey: String,
      masterLocations: jList[String],
      slaveLocations: jList[String]): Unit = {
    // check whether shuffleKey has registered
    if (!partitionLocationInfo.containsShuffle(shuffleKey)) {
      logWarning(s"Shuffle $shuffleKey not registered!")
      context.reply(DestroyResponse(
        StatusCode.SHUFFLE_NOT_REGISTERED,
        masterLocations,
        slaveLocations))
      return
    }

    val failedMasters = new jArrayList[String]()
    val failedSlaves = new jArrayList[String]()

    // destroy master locations
    if (masterLocations != null && !masterLocations.isEmpty) {
      masterLocations.asScala.foreach { loc =>
        val allocatedLoc = partitionLocationInfo.getMasterLocation(shuffleKey, loc)
        if (allocatedLoc == null) {
          failedMasters.add(loc)
        } else {
          allocatedLoc.asInstanceOf[WorkingPartition].getFileWriter.destroy()
        }
      }
      // remove master locations from WorkerInfo
      val releaseMasterLocations =
        partitionLocationInfo.removeMasterPartitions(shuffleKey, masterLocations)
      workerInfo.releaseSlots(shuffleKey, releaseMasterLocations._1)
    }
    // destroy slave locations
    if (slaveLocations != null && !slaveLocations.isEmpty) {
      slaveLocations.asScala.foreach { loc =>
        val allocatedLoc = partitionLocationInfo.getSlaveLocation(shuffleKey, loc)
        if (allocatedLoc == null) {
          failedSlaves.add(loc)
        } else {
          allocatedLoc.asInstanceOf[WorkingPartition].getFileWriter.destroy()
        }
      }
      // remove slave locations from worker info
      val releaseSlaveLocations =
        partitionLocationInfo.removeSlavePartitions(shuffleKey, slaveLocations)
      workerInfo.releaseSlots(shuffleKey, releaseSlaveLocations._1)
    }
    // reply
    if (failedMasters.isEmpty && failedSlaves.isEmpty) {
      logInfo(s"Destroy ${masterLocations.size()} master location and ${slaveLocations.size()}" +
        s" slave locations for $shuffleKey successfully.")
      context.reply(DestroyResponse(StatusCode.SUCCESS, List.empty.asJava, List.empty.asJava))
    } else {
      logInfo(s"Destroy ${failedMasters.size()}/${masterLocations.size()} master location and" +
        s"${failedSlaves.size()}/${slaveLocations.size()} slave location for" +
        s" $shuffleKey PartialSuccess.")
      context.reply(DestroyResponse(StatusCode.PARTIAL_SUCCESS, failedMasters, failedSlaves))
    }
  }

  private def handleGetWorkerInfos(context: RpcCallContext): Unit = {
    val list = new jArrayList[WorkerInfo]()
    list.add(workerInfo)
    context.reply(GetWorkerInfosResponse(StatusCode.SUCCESS, list.asScala.toList: _*))
  }

  private def handleThreadDump(context: RpcCallContext): Unit = {
    val threadDump = Utils.getThreadDump()
    context.reply(ThreadDumpResponse(threadDump))
  }
}
