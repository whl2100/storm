/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.elasticsearch.bolt;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.elasticsearch.common.EsConfig;
import org.apache.storm.elasticsearch.common.EsTestUtil;
import org.apache.storm.elasticsearch.common.EsTupleMapper;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

public class EsIndexTopology {

    static final String SPOUT_ID = "spout";
    static final String BOLT_ID = "bolt";
    static final String TOPOLOGY_NAME = "elasticsearch-test-topology1";

    public static void main(String[] args) throws Exception {
        Config config = new Config();
        config.setNumWorkers(1);
        TopologyBuilder builder = new TopologyBuilder();
        UserDataSpout spout = new UserDataSpout();
        builder.setSpout(SPOUT_ID, spout, 1);
        EsTupleMapper tupleMapper = EsTestUtil.generateDefaultTupleMapper();
        EsConfig esConfig = new EsConfig("http://localhost:9300");
        builder.setBolt(BOLT_ID, new EsIndexBolt(esConfig, tupleMapper), 1).shuffleGrouping(SPOUT_ID);

        EsTestUtil.startEsNode();
        EsTestUtil.waitForSeconds(5);
        StormSubmitter.submitTopology(TOPOLOGY_NAME, config, builder.createTopology());
    }

    public static class UserDataSpout extends BaseRichSpout {
        private ConcurrentHashMap<UUID, Values> pending;
        private SpoutOutputCollector collector;
        private String[] sources = {
                "{\"user\":\"user1\"}",
                "{\"user\":\"user2\"}",
                "{\"user\":\"user3\"}",
                "{\"user\":\"user4\"}"
        };
        private int index = 0;
        private int count = 0;
        private long total = 0L;
        private String indexName = "index1";
        private String typeName = "type1";

        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("source", "index", "type", "id"));
        }

        public void open(Map<String, Object> config, TopologyContext context,
                         SpoutOutputCollector collector) {
            this.collector = collector;
            this.pending = new ConcurrentHashMap<UUID, Values>();
        }

        public void nextTuple() {
            String source = sources[index];
            UUID msgId = UUID.randomUUID();
            Values values = new Values(source, indexName, typeName, msgId);
            this.pending.put(msgId, values);
            this.collector.emit(values, msgId);
            index++;
            if (index >= sources.length) {
                index = 0;
            }
            count++;
            total++;
            if (count > 1000) {
                count = 0;
                System.out.println("Pending count: " + this.pending.size() + ", total: " + this.total);
            }
            Thread.yield();
        }

        public void ack(Object msgId) {
            this.pending.remove(msgId);
        }

        public void fail(Object msgId) {
            System.out.println("**** RESENDING FAILED TUPLE");
            this.collector.emit(this.pending.get(msgId), msgId);
        }
    }
}
