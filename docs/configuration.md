---
hide:
  - navigation

license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Configuration Guide
===
This documentation contains Celeborn configuration details and a tuning guide.

## Important Configurations

### Environment Variables

- `CELEBORN_WORKER_MEMORY=4g`
- `CELEBORN_WORKER_OFFHEAP_MEMORY=24g`

Celeborn workers tend to improve performance by using off-heap buffers.
Off-heap memory requirement can be estimated as below:

```math
numDirs = `celeborn.worker.storage.dirs`      # the amount of directory will be used by Celeborn storage
bufferSize = `rss.worker.flush.buffer.size`   # the amount of memory will be used by a single flush buffer 
off-heap-memory = bufferSize * estimatedTasks * 2 + network memory
```

For example, if an Celeborn worker has 10 storage directories or disks and the buffer size is set to 256 KiB.
The necessary off-heap memory is 10 GiB.

NetWork memory will be consumed when netty reads from a TPC channel, there will need some extra
memory. Empirically, Celeborn worker off-heap memory should be set to `(numDirs  * bufferSize * 1.2)`.

### Client-side Configurations

| Key                                               | Default                                                       | Description                                                                                                                                                                                                                                                                                        |
|---------------------------------------------------|---------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| spark.celeborn.master.endpoints                   | localhost:9097                                                | Endpoints of master nodes, allowed pattern is: `<host1>:<port1>[,<host2>:<port2>]*`, e.g. `clb1:9097,clb2:9098,clb3:9099`.                                                                                                                                                                         |
| spark.rss.push.data.buffer.size                   | 64k                                                           | Amount of reducer partition buffer memory. Buffered data will be sent to Celeborn worker if buffer is full. For performance consideration keep this buffer size higher than 32K. Example: If reducer amount is 2000,buffer size is 64K and task will consume up to 64K * 2000 = 125 M heap memory. |
| spark.rss.push.data.queue.capacity                | 512                                                           | Push buffer queue size for a task. The maximum memory is `spark.rss.push.data.buffer.size` * `spark.rss.push.data.queue.capacity`(64K * 512 = 32M)                                                                                                                                                 |
| spark.rss.push.data.maxReqsInFlight               | 32                                                            | Amount of netty in-flight requests. The maximum memory is `rss.push.data.maxReqsInFlight` * `spark.celeborn.push.buffer.size` * compression ratio(1 in worst case)(64K * 32 = 2M )                                                                                                                 |
| spark.rss.limit.inflight.timeout                  | 240s                                                          | Timeout for netty in-flight requests to be done.                                                                                                                                                                                                                                                   |
| spark.rss.fetch.chunk.timeout                     | 120s                                                          | Timeout for a task to fetch chunk.                                                                                                                                                                                                                                                                 |
| spark.rss.fetch.chunk.maxReqsInFlight             | 3                                                             | Amount of in-flight chunk fetch request.                                                                                                                                                                                                                                                           |
| spark.rss.data.io.threads                         | 8                                                             | Amount of thread count for task to push data.                                                                                                                                                                                                                                                      |
| spark.rss.push.data.replicate                     | true                                                          | When true the Celeborn worker will replicate shuffle data to another Celeborn worker to ensure shuffle data won't be lost after the node failure.                                                                                                                                                  |
| spark.rss.application.heartbeatInterval           | 10s                                                           | Application heartbeat interval.                                                                                                                                                                                                                                                                    |
| spark.rss.stage.end.timeout                       | 240s                                                          | Time out for StageEnd.                                                                                                                                                                                                                                                                             |
| spark.rss.shuffle.writer.mode                     | hash                                                          | Celeborn support two different shuffle writers. Hash-based shuffle writer works fine when shuffle partition count is normal. Sort-based shuffle writer works fine when memory pressure is high or shuffle partition count it huge.                                                                 |
| spark.rss.client.compression.codec                | lz4                                                           | The codec used to compress shuffle data. By default, Celeborn provides two codecs: `lz4` and `zstd`.                                                                                                                                                                                               |
| spark.rss.client.compression.zstd.level           | 1                                                             | Compression level for Zstd compression codec, its value should be an integer between -5 and 22. Increasing the compression level will result in better compression at the expense of more CPU and memory.                                                                                          |
| spark.rss.identity.provider                       | `org.apache.celeborn.common.identity.DefaultIdentityProvider` | Identity provider class name. Default value use `DefaultIdentityProvider`, return `UserIdentifier` with default tenant id and username from `UsergroupInformation`.                                                                                                                                |  
| spark.rss.range.read.filter.enabled               | false                                                         | If a spark application have skewed partition, this value can set to true to improve performance.                                                                                                                                                                                                   |  
| spark.rss.columnar.shuffle.enabled                | false                                                         | When true the Celeborn will convert shuffle mode to columnar shuffle.                                                                                                                                                                                                                              |  
| spark.rss.columnar.shuffle.encoding.enabled       | false                                                         | When true the Celeborn will use encoding in columnar shuffle.                                                                                                                                                                                                                                      |  
| spark.rss.columnar.shuffle.batch.size             | 10000                                                         | Controls the size of columnar batche for columnar shuffle.                                                                                                                                                                                                                                         |  
| spark.rss.columnar.shuffle.offheap.vector.enabled | false                                                         | When true use OffHeapColumnVector in columnar batch.                                                                                                                                                                                                                                               |  
| spark.rss.columnar.shuffle.max.dict.factor        | 0.3                                                           | When using dictionary encoding, the maximum proportion of the dictionary in columnar batch.                                                                                                                                                                                                        |  
| spark.rss.change.partition.batch.enabled          | false                                                         | When true, Celeborn support handle change partition request in batch.                                                                                                                                                                                                                              |
| spark.rss.change.partition.numThreads             | 8                                                             | Threads number for LifecycleManager to handle change partition request in batch.                                                                                                                                                                                                                   |
| spark.rss.change.partition.batchInterval          | 100ms                                                         | Batch handling interval for LifecycleManager handle change partition requests in batch.                                                                                                                                                                                                            |

### Celeborn Master Configurations

| Key                                          | Default                 | Description                                                                                                                  |
|----------------------------------------------|-------------------------|------------------------------------------------------------------------------------------------------------------------------|
| rss.worker.timeout                           | 120s                    |                                                                                                                              |
| rss.application.timeout                      | 120s                    |                                                                                                                              |
| rss.rpc.io.clientThreads                     | min{64, availableCores} |                                                                                                                              |
| rss.rpc.io.serverThreads                     | min{64, availableCores} |                                                                                                                              |
| rss.master.port.maxretry                     | 1                       | When Celeborn master port is occupied,we will retry for max retry times.                                                     |
| rss.rpc.io.numConnectionsPerPeer             | 1                       | Connections between hosts are reused in order to reduce connection.                                                          |
| celeborn.ha.enabled                          | false                   | When true, Celeborn will activate raft implementation and sync shared data on master clusters.                               |
| celeborn.master.endpoints                    | localhost:9097          | Endpoints of master nodes, allowed pattern is: `<host1>:<port1>[,<host2>:<port2>]*`, e.g. `clb1:9097,clb2:9098,clb3:9099`.   |
| rss.ha.service.id                            |                         | When this config is empty, Celeborn master will refuse to startup.                                                           |
| rss.ha.nodes.{serviceId}                     |                         | Nodes list that deploy Celeborn master. ServiceId is `rss.ha.service.id`                                                     |
| rss.ha.address.{serviceId}.{node}            | localhost:9872          | Celeborn master's rpc address for raft implementation. Port can be ignored and defaults to 9872                              |
| rss.ha.port.{serviceId}.{node}               | 9872                    | HA port between multi-master, default port is 9872. If you want to customize the HA port, you need to list all nodes' ports. |
| rss.ha.storage.dir                           | /tmp/ratis              | Directory of Celeborn master to store ratis metadata.                                                                        |
| rss.ha.ratis.snapshot.auto.trigger.enabled   | true                    | Weather to enable raft implementation's snapshot.                                                                            |
| rss.ha.ratis.snapshot.auto.trigger.threshold | 200000                  |                                                                                                                              |
| rss.offer.slots.algorithm                    | roundrobin              | There is two algorithms loadaware and roundrobin. loadaware is recommended unless you encountered some malfunctions.         |

### Celeborn Worker Configurations

| Key                                      | Default                                           | Description                                                                                                                                                                                                                                                                                                                                                                                                       |
|------------------------------------------|---------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| rss.worker.base.dirs                     |                                                   | Directory list to store shuffle data. Storage size limit can be set for each directory. For the sake of performance, there should be no more than 2 directories on the same disk partition if you are using HDD. There can be 4 or more directories can run on the same disk partition if you are using SSD. For example: dir1[:capacity=][:disktype=][:flushthread=],dir2[:capacity=][:disktype=][:flushthread=] |
| rss.worker.workingDirName                | `hadoop/rss-worker/shuffle_data`                  |                                                                                                                                                                                                                                                                                                                                                                                                                   |
| rss.worker.flush.buffer.size             | 256K                                              |                                                                                                                                                                                                                                                                                                                                                                                                                   |
| rss.chunk.size                           | 8m                                                | Max chunk size of reducer's merged shuffle data. For example, if a reducer's shuffle data is 128 M and the data will need 16 fetch chunk requests to fetch.                                                                                                                                                                                                                                                       |
| rss.push.io.threads                      | `rss.worker.base.dirs` * 2                        |                                                                                                                                                                                                                                                                                                                                                                                                                   |
| rss.fetch.io.threads                     | `rss.worker.base.dirs` * 2                        |                                                                                                                                                                                                                                                                                                                                                                                                                   |
| rss.master.address                       |                                                   | Single master mode: address(host:port) of Celeborn Master,necessary                                                                                                                                                                                                                                                                                                                                               |
| rss.master.host                          |                                                   | Single master: host of Celeborn Master                                                                                                                                                                                                                                                                                                                                                                            |
| rss.ha.master.hosts                      |                                                   | Ha mode: hosts of Celeborn Master                                                                                                                                                                                                                                                                                                                                                                                 |
| rss.master.port                          |                                                   | Port of Celeborn Master                                                                                                                                                                                                                                                                                                                                                                                           |


### Metrics

| Key                                     | Default | Description |
|-----------------------------------------|---------|-------------|
| celeborn.metrics.enabled                | true    |             |
| celeborn.master.metrics.prometheus.host | 0.0.0.0 |             |
| celeborn.master.metrics.prometheus.port | 9098    |             |
| celeborn.worker.metrics.prometheus.port | 0.0.0.0 |             |
| celeborn.worker.metrics.prometheus.port | 9096    |             |

#### metrics.properties

```properties
*.sink.csv.class=org.apache.celeborn.common.metrics.sink.CsvSink
*.sink.prometheusServlet.class=org.apache.celeborn.common.metrics.sink.PrometheusServlet
```

## Tuning

Assume we have a cluster described as below:
5 Celeborn Workers with 20 GB off-heap memory and 10 disks.
As we need to reserve 20% off-heap memory for netty,
so we could assume 16 GB off-heap memory can be used for flush buffers.

If `spark.celeborn.push.buffer.size` is 64 KB, we can have in-flight requests up to 1310720.
If you have 8192 mapper tasks, you could set `spark.rss.push.data.maxReqsInFlight=160` to gain performance improvements.

If `rss.worker.flush.buffer` is 256 KB, we can have total slots up to 327680 slots.

## Worker Recover Status After Restart

`ShuffleClient` records the shuffle partition location's host, service port, and filename,
to support workers recovering reading existing shuffle data after worker restart,
during worker shutdown, workers should store the meta about reading shuffle partition files in LevelDB,
and restore the meta after restarting workers, also workers should keep a stable service port to support
`ShuffleClient` retry reading data. Users should set `rss.worker.graceful.shutdown` to `true` and
set below service port with stable port to support worker recover status.
```
rss.worker.rpc.port
rss.fetchserver.port
rss.pushserver.port
rss.replicateserver.port
```

## APPENDIX Celeborn Configuration List

### Environment Variables(rss-env.sh)

| Key                              | Default                                         | Description |
|----------------------------------|-------------------------------------------------|-------------|
| `CELEBORN_HOME`                  | ``$(cd "`dirname "$0"`"/..; pwd)``              |             |
| `CELEBORN_CONF_DIR`              | `${CELEBORN_CONF_DIR:-"${CELEBORN_HOME}/conf"}` |             |
| `CELEBORN_MASTER_MEMORY`         | 1 GB                                            |             |
| `CELEBORN_WORKER_MEMORY`         | 1 GB                                            |             |
| `CELEBORN_WORKER_OFFHEAP_MEMORY` | 1 GB                                            |             |
| `CELEBORN_MASTER_JAVA_OPTS`      |                                                 |             |
| `CELEBORN_WORKER_JAVA_OPTS`      |                                                 |             |
| `CELEBORN_PID_DIR`               | `${CELEBORN_HOME}/pids`                         |             |
| `CELEBORN_LOG_DIR`               | `${CELEBORN_HOME}/logs`                         |             |
| `CELEBORN_SSH_OPTS`              | `-o StrictHostKeyChecking=no`                   |             |
| `CELEBORN_SLEEP`                 |                                                 |             |

### DefaultConfigs(celeborn-defaults.conf)

| Key                                         | Default                          | Type    | Description                                                                                                                                     |
|---------------------------------------------|----------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `rss.push.data.buffer.size`                 | 64 KiB                           | String  |                                                                                                                                                 |
| `rss.push.data.queue.capacity`              | 512                              | int     |                                                                                                                                                 |
| `rss.push.data.maxReqsInFlight`             | 32                               | int     |                                                                                                                                                 |
| `rss.fetch.chunk.timeout`                   | 120 s                            | String  |                                                                                                                                                 |
| `rss.fetch.chunk.maxReqsInFlight`           | 3                                | int     |                                                                                                                                                 |
| `rss.push.data.replicate`                   | true                             | bool    |                                                                                                                                                 |
| `rss.worker.timeout`                        | 120 s                            | String  |                                                                                                                                                 |
| `rss.application.timeout`                   | 120 s                            | String  |                                                                                                                                                 |
| `rss.remove.shuffle.delay`                  | 60 s                             | String  |                                                                                                                                                 |
| `rss.get.blacklist.delay`                   | 30s                              | String  |                                                                                                                                                 |
| `celeborns.master.endpoints`                | `localhost:9097`                 | String  |                                                                                                                                                 |
| `rss.worker.replicate.numThreads`           | 64                               | int     |                                                                                                                                                 |
| `rss.worker.asyncCommitFiles.numThreads`    | 32                               | int     |                                                                                                                                                 |
| `rss.worker.flush.buffer.size`              | 256 KiB                          | String  |                                                                                                                                                 |
| `rss.worker.fetch.chunk.size`               | 8 MiB                            | String  |                                                                                                                                                 |
| `rss.rpc.max.parallelism`                   | 1024                             | int     |                                                                                                                                                 |
| `rss.register.shuffle.max.retry`            | 3                                | int     |                                                                                                                                                 |
| `rss.register.shuffle.retry.wait`           | 3s                               | int     |                                                                                                                                                 |
| `rss.reserve.slots.max.retry`               | 3                                | int     |                                                                                                                                                 |
| `rss.reserve.slots.retry.wait`              | 3s                               | String  |                                                                                                                                                 |
| `rss.flush.timeout`                         | 240s                             | String  |                                                                                                                                                 |
| `rss.application.expire.duration`           | 1d                               | String  |                                                                                                                                                 |
| `rss.worker.base.dirs`                      |                                  | String  |                                                                                                                                                 |
| `rss.worker.workingDirName`                 | `hadoop/rss-worker/shuffle_data` | String  |                                                                                                                                                 |
| `rss.worker.base.dir.prefix`                | `/mnt/disk`                      | String  |                                                                                                                                                 |
| `rss.worker.base.dir.number`                | 16                               | int     |                                                                                                                                                 |
| `rss.worker.unavailable.dirs.remove`        | true                             | bool    |                                                                                                                                                 |
| `rss.stage.end.timeout`                     | 120 s                            | String  |                                                                                                                                                 |
| `rss.limit.inflight.timeout`                | 240 s                            | String  |                                                                                                                                                 |
| `rss.limit.inflight.sleep.delta`            | 50 ms                            | String  |                                                                                                                                                 |
| `rss.pushserver.port`                       | 0                                | int     |                                                                                                                                                 |
| `rss.fetchserver.port`                      | 0                                | int     |                                                                                                                                                 |
| `rss.register.worker.timeout`               | 180 s                            | String  |                                                                                                                                                 |
| `rss.master.port.maxretry`                  | 1                                | int     |                                                                                                                                                 |
| `rss.pushdata.retry.thread.num`             | 8                                | int     |                                                                                                                                                 |
| `rss.merge.push.data.threshold`             | 1 MiB                            | String  |                                                                                                                                                 |
| `rss.driver.metaService.port`               | 0                                | int     |                                                                                                                                                 |
| `rss.worker.closeIdleConnections`           | false                            | bool    |                                                                                                                                                 |
| `rss.ha.enabled`                            | false                            | bool    |                                                                                                                                                 |
| `rss.ha.master.hosts`                       | `rss.master.host`                | String  |                                                                                                                                                 |
| `rss.ha.service.id`                         |                                  | String  |                                                                                                                                                 |
| `rss.ha.nodes.<serviceId>`                  |                                  | String  |                                                                                                                                                 |
| `rss.ha.address.<serviceId>.<nodeId>`       |                                  | String  |                                                                                                                                                 |
| `rss.ha.port`                               | 9872                             | int     |                                                                                                                                                 |
| `rss.ha.storage.dir`                        | `/tmp/ratis`                     | String  |                                                                                                                                                 |
| `rss.device.monitor.enabled`                | true                             | boolean | Whether to enable device monitor                                                                                                                |
| `rss.device.monitor.checklist`              | `readwrite,diskusage`            | String  | Select what the device needs to detect ; The options include iohang, readOrWrite and diskUsage.                                                 |
| `rss.disk.check.interval`                   | 15s                              | String  | How frequency DeviceMonitor checks IO hang                                                                                                      |
| `rss.slow.flush.interval`                   | 10s                              | String  | Threshold that determines slow flush                                                                                                            |
| `rss.sys.block.dir`                         | "/sys/block"                     | String  | Directory to read device stat and inflight                                                                                                      |
| `rss.create.file.writer.retry.count`        | 3                                | int     | Worker create FileWriter retry count                                                                                                            |
| `rss.worker.checkFileCleanRetryTimes`       | 3                                | int     | The number of retries for a worker to check if the working directory is cleaned up before registering with the master.                          |
| `rss.worker.checkFileCleanTimeoutMs`        | 1000ms                           | String  | The wait time per retry for a worker to check if the working directory is cleaned up before registering with the master.                        |
| `rss.worker.diskFlusherShutdownTimeoutMs`   | 3000ms                           | String  | The timeout to wait for diskOperators to execute remaining jobs before being shutdown immediately.                                              |
| `rss.worker.status.check.timeout`           | 10s                              | String  | Worker device check timeout                                                                                                                     |
| `rss.worker.offheap.memory.critical.ratio`  | 0.9                              | float   | Worker direct memory usage critical level ratio                                                                                                 |
| `rss.worker.memory.check.interval`          | 10                               | int     | Timeunit is millisecond                                                                                                                         |
| `rss.worker.memory.report.interval`         | 10s                              | String  | Timeunit is second                                                                                                                              |
| `rss.partition.split.threshold`             | 256m                             | String  | Shuffle file size threshold, if file size exceeds this, trigger split                                                                           |
| `rss.partition.split.minimum.size`          | 1m                               | String  | Minimum Shuffle file size to trigger split, file size smaller than this will not trigger split                                                  |
| `rss.partition.split.mode`                  | soft                             | String  | soft, the shuffle file size might be larger than split threshold ; hard, the shuffle file size will be limited to split threshold               |
| `rss.client.split.pool.size`                | 8                                | int     | Thread number to process shuffle split request in shuffle client.                                                                               |
| `rss.partition.sort.timeout`                | 220                              | int     | Timeout for a shuffle file to sort                                                                                                              |
| `rss.partition.sort.memory.max.ratio`       | 0.1                              | double  | Max ratio of sort memory                                                                                                                        |
| `rss.pause.pushdata.memory.ratio`           | 0.85                             | double  | If direct memory usage reach this limit, worker will stop receive from executor                                                                 |
| `rss.pause.replicate.memory.ratio`          | 0.95                             | double  | If direct memory usage reach  this limit, worker will stop receive from executor and other worker                                               |
| `rss.resume.memory.ratio`                   | 0.5                              | double  | If direct memory usage is less than this  limit, worker will resume receive                                                                     |
| `rss.worker.initialReserveSingleSortMemory` | 1mb                              | string  | Initial reserve memory when sorting a shuffle file off-heap.                                                                                    |
| `rss.storage.hint`                          | memory                           | string  | Available enumerations : memory,ssd,hdd,hdfs,oss                                                                                                |
| `rss.initial.partition.size`                | 64m                              | string  | Initial estimated partition size, default is 64m, and it will change according to runtime stats.                                                |
| `rss.minimum.estimate.partition.size`       | 8m                               | string  | Ignore partition size smaller than this configuration for partition size estimation.                                                            |
| `rss.disk.flusher.useMountPoint`            | bool                             | true    | True means that each disk will get one disk flush. False means that a disk flush will be attached to a working directory.                       |
| `rss.rpc.askTimeout`                        | 240s                             | string  | Timeout for sending rpc messages.                                                                                                               |
| `rss.rpc.lookupTimeout`                     | 30s                              | string  | Timeout for creating new connection. This value should be less than `rss.worker.timeout` to avoid worker lost in HA mode.                       |
| `rss.haclient.rpc.askTimeout`               | 30s                              | string  | Timeout for HA client sending rpc messages. This value should be less than `rss.worker.timeout` to avoid worker lost in HA mode.                |
| `rss.disk.groups`                           | 5                                | int     | This value determines how many groups the disks will be separate. It can be calculated as following:`(total disk count / ssd disk count ) + 1`. |
| `rss.disk.group.gradient`                   | 0.1                              | double  | This value means how many more workload will be placed into a faster disk group than a slower group.                                            |
| `rss.disk.minimum.reserve.size`             | 10G                              | long    | The minimum size reserved on a disk. If disk's usable space is less than this, then Master will try not to allocate slots on it.                |
