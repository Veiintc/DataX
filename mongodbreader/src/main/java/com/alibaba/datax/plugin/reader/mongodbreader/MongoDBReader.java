package com.alibaba.datax.plugin.reader.mongodbreader;
import com.alibaba.datax.common.element.*;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordSender;
import com.alibaba.datax.common.spi.Reader;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.reader.mongodbreader.util.CollectionSplitUtil;
import com.alibaba.datax.plugin.reader.mongodbreader.util.MongoUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.mongodb.*;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.*;

public class MongoDBReader extends Reader {

    public static class Job extends Reader.Job {

        private Configuration originalConfig = null;

        private MongoClient mongoClient;

        private String userName = null;
        private String password = null;

        @Override
        public List<Configuration> split(int adviceNumber) {
            return CollectionSplitUtil.doSplit(originalConfig,adviceNumber,mongoClient);
        }

        @Override
            public void init() {
            this.originalConfig = super.getPluginJobConf();
                this.userName = originalConfig.getString(KeyConstant.MONGO_USER_NAME);
                this.password = originalConfig.getString(KeyConstant.MONGO_USER_PASSWORD);
                String database =  originalConfig.getString(KeyConstant.MONGO_DB_NAME);
            if(!Strings.isNullOrEmpty(this.userName) && !Strings.isNullOrEmpty(this.password)) {
                this.mongoClient = MongoUtil.initCredentialMongoClient(originalConfig,userName,password,database);
            } else {
                this.mongoClient = MongoUtil.initMongoClient(originalConfig);
            }
        }

        @Override
        public void destroy() {

        }
    }


    public static class Task extends Reader.Task {

        private Configuration readerSliceConfig;

        private MongoClient mongoClient;

        private String userName = null;
        private String password = null;

        private String database = null;
        private String collection = null;

        private String query = null;

        private JSONArray mongodbColumnMeta = null;
        private Long batchSize = null;
        /**
         * 用来控制每个task取值的offset
         */
        private Long skipCount = null;
        /**
         * 每页数据的大小
         */
        private int pageSize = 1000;

        @Override
        public void startRead(RecordSender recordSender) {

            if(batchSize == null ||
                             mongoClient == null || database == null ||
                             collection == null  || mongodbColumnMeta == null) {
                throw DataXException.asDataXException(MongoDBReaderErrorCode.ILLEGAL_VALUE,
                        MongoDBReaderErrorCode.ILLEGAL_VALUE.getDescription());
            }
            MongoDatabase db = mongoClient.getDatabase(database);
            MongoCollection col = db.getCollection(this.collection);
            BsonDocument sort = new BsonDocument();
            sort.append(KeyConstant.MONGO_PRIMIARY_ID_META, new BsonInt32(1));

            long pageCount = batchSize / pageSize;
            int modCount = (int)(batchSize % pageSize);
            ObjectId lastObjectId = null;           // 最后一次的读取数据的ObjectId

            BsonDocument queryObj = null;
            if (!Strings.isNullOrEmpty(query)) {
                // 有query的情况
                queryObj = BsonDocument.parse(query);
            } else {
                // findAll情况
                queryObj = new BsonDocument();
            }

            for(int i = 0; i <= pageCount; i++) {
                if(modCount == 0 && i == pageCount) {
                    break;
                }
                if (i == pageCount) {
                    pageSize = modCount;
                }
                MongoCursor<Document> dbCursor = null;

                // 根据 https://github.com/alibaba/DataX/issues/200 提供代码修改
                // 先根据_id做排序，记录最后次读取到的ObjectId，做gt查询，不用skip。
                if (lastObjectId != null) {
                    queryObj.put(KeyConstant.MONGO_PRIMIARY_ID_META, new BsonDocument("$gt", new BsonObjectId(lastObjectId)));
                    dbCursor = col.find(queryObj).sort(sort).limit(pageSize).iterator();
                } else {
                    // 跳过设定的初始skip
                    if (queryObj.size() > 0) {
                        dbCursor = col.find(queryObj).sort(sort).skip(skipCount.intValue()).limit(pageSize).iterator();
                    } else {
                        // 默认没有query
                        dbCursor = col.find().sort(sort).skip(skipCount.intValue()).limit(pageSize).iterator();
                    }
                }

                while (dbCursor.hasNext()) {
                    Document item = dbCursor.next();
                    Record record = recordSender.createRecord();
                    Iterator columnItera = mongodbColumnMeta.iterator();
                    try {
                    	lastObjectId = item.getObjectId(KeyConstant.MONGO_PRIMIARY_ID_META);        // 读取的最后条记录的ObjectId

                    }catch (Exception e) {
						// TODO: handle exception
                    	continue;																	//过滤脏数据

					}
                    while (columnItera.hasNext()) {
                        JSONObject column = (JSONObject)columnItera.next();
                        Object tempCol = item.get(column.getString(KeyConstant.COLUMN_NAME));
                        if (tempCol == null) {
                            record.addColumn(new StringColumn(""));
                            continue;
                        }
                        if (tempCol instanceof Double) {
                            record.addColumn(new DoubleColumn((Double) tempCol));
                        } else if (tempCol instanceof Boolean) {
                            record.addColumn(new BoolColumn((Boolean) tempCol));
                        } else if (tempCol instanceof Date) {
                            record.addColumn(new DateColumn((Date) tempCol));
                        } else if (tempCol instanceof Integer) {
                            record.addColumn(new LongColumn((Integer) tempCol));
                        }else if (tempCol instanceof Long) {
                            record.addColumn(new LongColumn((Long) tempCol));
                        } else {
                            if(KeyConstant.isArrayType(column.getString(KeyConstant.COLUMN_TYPE))) {
                                String splitter = column.getString(KeyConstant.COLUMN_SPLITTER);
                                if(Strings.isNullOrEmpty(splitter)) {
                                    throw DataXException.asDataXException(MongoDBReaderErrorCode.ILLEGAL_VALUE,
                                            MongoDBReaderErrorCode.ILLEGAL_VALUE.getDescription());
                                } else {
                                    ArrayList array = (ArrayList)tempCol;
                                    String tempArrayStr = Joiner.on(splitter).join(array);
                                    record.addColumn(new StringColumn(tempArrayStr));
                                }
                            } else {
                                record.addColumn(new StringColumn(tempCol.toString()));
                            }
                        }
                    }
                    recordSender.sendToWriter(record);
                }
                skipCount += pageSize;
            }
        }

        @Override
        public void init() {
            this.readerSliceConfig = super.getPluginJobConf();
                this.userName = readerSliceConfig.getString(KeyConstant.MONGO_USER_NAME);
                this.password = readerSliceConfig.getString(KeyConstant.MONGO_USER_PASSWORD);
                this.database = readerSliceConfig.getString(KeyConstant.MONGO_DB_NAME);
            if(!Strings.isNullOrEmpty(userName) && !Strings.isNullOrEmpty(password)) {
                mongoClient = MongoUtil.initCredentialMongoClient(readerSliceConfig,userName,password,database);
            } else {
                mongoClient = MongoUtil.initMongoClient(readerSliceConfig);
            }
            
            this.collection = readerSliceConfig.getString(KeyConstant.MONGO_COLLECTION_NAME);
            this.query = readerSliceConfig.getString(KeyConstant.MONGO_QUERY);
            this.mongodbColumnMeta = JSON.parseArray(readerSliceConfig.getString(KeyConstant.MONGO_COLUMN));
            this.batchSize = readerSliceConfig.getLong(KeyConstant.BATCH_SIZE);
            this.skipCount = readerSliceConfig.getLong(KeyConstant.SKIP_COUNT);
        }

        @Override
        public void destroy() {

        }
    }
}
