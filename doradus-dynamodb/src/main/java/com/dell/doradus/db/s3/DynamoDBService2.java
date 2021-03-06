/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.db.s3;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import com.amazonaws.services.dynamodbv2.util.Tables;
import com.dell.doradus.common.Utils;
import com.dell.doradus.service.db.ColumnDelete;
import com.dell.doradus.service.db.ColumnUpdate;
import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DBTransaction;
import com.dell.doradus.service.db.DColumn;
import com.dell.doradus.service.db.DRow;
import com.dell.doradus.service.db.RowDelete;
import com.dell.doradus.service.db.Tenant;
import com.dell.doradus.utilities.Timer;

public class DynamoDBService2 extends DBService {
	private static final byte[] EMPTY_VALUE = new byte[] { (byte)0 };
	private static final int[] RETRY_SLEEPS = new int[] { 100, 500, 1000, 2000, 5000, 10000, 30000 };
    protected final Logger m_logger = LoggerFactory.getLogger(getClass());
	private long m_readCapacityUnits;
	private long m_writeCapacityUnits;
    private AmazonDynamoDBClient m_client;
    
    public DynamoDBService2(Tenant tenant) { 
        super(tenant);
        
        String accessKey = getParamString("ddb-access-key");
        String secretKey = getParamString("ddb-secret-key");
        String endpoint = getParamString("ddb-endpoint");
        m_readCapacityUnits = getParamInt("ddb-read-capacity-units", 1);
        m_writeCapacityUnits = getParamInt("ddb-write-capacity-units", 1);
        
        BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey); 
        m_client = new AmazonDynamoDBClient(awsCredentials);
        m_client.setEndpoint(endpoint);
        // try to connect to check the connection
        m_client.listTables();
        
        m_logger.info("Started DynamoDB service. Endpoint: {}, read/write capacity units for new namespaces: {}/{}",
                      new Object[] {endpoint, m_readCapacityUnits, m_writeCapacityUnits});
    }
    
    @Override
    protected void stopService() {
        m_client.shutdown();
    }

    @Override
    public void createNamespace() {
        String table = getTenant().getName();
        if(Tables.doesTableExist(m_client, table)) return;
        m_logger.info("Creating table: {}", table);
        CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(table)
            .withKeySchema(
            	new KeySchemaElement()
	                .withAttributeName("key")
	                .withKeyType(KeyType.HASH),
                new KeySchemaElement()
	                .withAttributeName("column")
	                .withKeyType(KeyType.RANGE))
            .withAttributeDefinitions(
            	new AttributeDefinition()
	                .withAttributeName("key")
	                .withAttributeType(ScalarAttributeType.S),
            	new AttributeDefinition()
	                .withAttributeName("column")
	                .withAttributeType(ScalarAttributeType.S))
            .withProvisionedThroughput(new ProvisionedThroughput()
                .withReadCapacityUnits(m_readCapacityUnits)
                .withWriteCapacityUnits(m_writeCapacityUnits));
        m_client.createTable(createTableRequest);
        try {
            Tables.awaitTableToBecomeActive(m_client, table);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);  
        }
    }
    
    @Override
    public void dropNamespace() {
        m_client.deleteTable(getTenant().getName());
    }
    
    @Override
    public void createStoreIfAbsent(String storeName, boolean bBinaryValues) {
    	// nothing to do
    }

    @Override
    public void deleteStoreIfPresent(String storeName) {
    	DBTransaction dbTran = new DBTransaction(getTenant());
    	for(DRow row: getAllRows(storeName)) {
    		dbTran.deleteRow(storeName, row.getKey());
    	}
    	commit(dbTran);
    }

    public void commit(DBTransaction dbTran) {
    	Timer t = new Timer();
        List<WriteRequest> list = new ArrayList<>();
        
        for(ColumnUpdate mutation: dbTran.getColumnUpdates()) {
        	Map<String, AttributeValue> item = getPrimaryKey(mutation.getStoreName() + "_" + mutation.getRowKey(), mutation.getColumn().getName());
        	byte[] value = mutation.getColumn().getRawValue();
        	if(value.length == 0) value = EMPTY_VALUE;
        	item.put("value", new AttributeValue().withB(ByteBuffer.wrap(value)));
        	list.add(new WriteRequest().withPutRequest(new PutRequest(item)));
        	
        	if(list.size() >= 25) {
        		commitPartial(list);
        	}
        }

        for(ColumnDelete mutation: dbTran.getColumnDeletes()) {
        	Map<String, AttributeValue> item = getPrimaryKey(mutation.getStoreName() + "_" + mutation.getRowKey(), mutation.getColumnName());
        	list.add(new WriteRequest().withDeleteRequest(new DeleteRequest(item)));
        	
        	if(list.size() >= 25) {
        		commitPartial(list);
        	}
        	
        }

        for(RowDelete mutation: dbTran.getRowDeletes()) {
        	for(DColumn c: getColumnSlice(mutation.getStoreName(), mutation.getRowKey(), null, null)) {
            	Map<String, AttributeValue> item = getPrimaryKey(mutation.getStoreName() + "_" + mutation.getRowKey(), c.getName());
            	list.add(new WriteRequest().withDeleteRequest(new DeleteRequest(item)));

            	if(list.size() >= 25) {
            		commitPartial(list);
            	}

        	}
        }

    	if(list.size() > 0) {
    		commitPartial(list);
    	}
    	
    	m_logger.debug("Committed transaction to {} in {}", getTenant().getName(), t);
    }

    private void commitPartial(List<WriteRequest> list) {
    	Timer t = new Timer();
		Map<String, List<WriteRequest>> map = new HashMap<>();
		map.put(getTenant().getName(), list);
		BatchWriteItemResult result = m_client.batchWriteItem(new BatchWriteItemRequest(map));
		int retry = 0;
		while(result.getUnprocessedItems().size() > 0) {
			if(retry == RETRY_SLEEPS.length) throw new RuntimeException("All retries failed");
			m_logger.debug("Committing {} unprocessed items, retry: {}", result.getUnprocessedItems().size(), retry + 1);
			try {
				Thread.sleep(RETRY_SLEEPS[retry++]);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    		result = m_client.batchWriteItem(new BatchWriteItemRequest(result.getUnprocessedItems()));
		}
		m_logger.debug("Committed {} writes in {}", list.size(), t);
		list.clear();
    }
    
    @Override
    public List<DColumn> getColumns(String storeName, String rowKey, String startColumn, String endColumn, int count) {
    	Timer t = new Timer();
    	String key = storeName + "_" + rowKey;
    	HashMap<String,Condition> keyConditions = new HashMap<String,Condition>();
    	keyConditions.put("key", new Condition()
			.withComparisonOperator(ComparisonOperator.EQ)
			.withAttributeValueList(new AttributeValue().withS(key)));
    	if(startColumn != null && endColumn != null) {
        	keyConditions.put("column", new Condition()
    			.withComparisonOperator(ComparisonOperator.BETWEEN)
    			.withAttributeValueList(new AttributeValue().withS(startColumn), new AttributeValue(endColumn)));
    	} else if(startColumn != null) {
        	keyConditions.put("column", new Condition()
    			.withComparisonOperator(ComparisonOperator.GE)
    			.withAttributeValueList(new AttributeValue().withS(startColumn)));
    	} else if(endColumn != null) {
        	keyConditions.put("column", new Condition()
    			.withComparisonOperator(ComparisonOperator.LT)
    			.withAttributeValueList(new AttributeValue().withS(endColumn)));
    	}

		QueryRequest request = new QueryRequest()
			.withTableName(getTenant().getName())
			.withLimit(Math.min(100,  count))
			.withKeyConditions(keyConditions);
		QueryResult result = m_client.query(request);
		List<DColumn> list = fromItems(result.getItems());
        m_logger.debug("get columns range for {} in {}", getTenant().getName(), t);
    	return list;
    }

    @Override
    public List<DColumn> getColumns(String storeName, String rowKey, Collection<String> columnNames) {
    	Timer t = new Timer();
    	String namespace = getTenant().getName();
    	String key = storeName + "_" + rowKey;
    	List<DColumn> list = new ArrayList<>();
        
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for(String col: columnNames) {
        	keys.add(getPrimaryKey(key, col));
        	if(keys.size() >= 100) {
                KeysAndAttributes x = new KeysAndAttributes().withKeys(keys);
    			Map<String, KeysAndAttributes> map = new HashMap<>();
                map.put(namespace, x);
    			BatchGetItemResult result = m_client.batchGetItem(new BatchGetItemRequest(map));
    			if(result.getUnprocessedKeys().size() > 0) throw new RuntimeException("Could not process all items");
    			list.addAll(fromItems(result.getResponses().get(namespace)));
    			keys.clear();
        	}
        }
        if(keys.size() > 0) {
            KeysAndAttributes x = new KeysAndAttributes().withKeys(keys);
			Map<String, KeysAndAttributes> map = new HashMap<>();
            map.put(namespace, x);
			BatchGetItemResult result = m_client.batchGetItem(new BatchGetItemRequest(map));
			if(result.getUnprocessedKeys().size() > 0) throw new RuntimeException("Could not process all items");
			list.addAll(fromItems(result.getResponses().get(namespace)));
			keys.clear();
        }
        m_logger.debug("get columns for {} in {}", namespace, t);
    	return list;
    }

    @Override
    public List<String> getRows(String storeName, String continuationToken, int count) {
    	Timer t = new Timer();
    	ScanRequest scanRequest = new ScanRequest(getTenant().getName());
        scanRequest.setAttributesToGet(Arrays.asList("key")); // attributes to get
        //if (continuationToken != null) {
        //	scanRequest.setExclusiveStartKey(getPrimaryKey(storeName + "_" + continuationToken, "\u007F"));
        //} else {
        //	scanRequest.setExclusiveStartKey(getPrimaryKey(storeName + "_", "\0"));
        //}

        Set<String> rowKeys = new HashSet<>();
        while (rowKeys.size() < count) {
            ScanResult scanResult = m_client.scan(scanRequest);
            List<Map<String, AttributeValue>> itemList = scanResult.getItems();
            if (itemList.size() == 0) break;
            for (Map<String, AttributeValue> attributeMap : itemList) {
                AttributeValue rowAttr = attributeMap.get("key");
                if(!rowAttr.getS().startsWith(storeName)) continue;
                String name = rowAttr.getS().substring(storeName.length() + 1);
                if(continuationToken != null && continuationToken.compareTo(name) >= 0) continue;
                rowKeys.add(name);
            }
            Map<String, AttributeValue> lastEvaluatedKey = scanResult.getLastEvaluatedKey();
            if (lastEvaluatedKey == null) break;
        	scanRequest.setExclusiveStartKey(getPrimaryKey(lastEvaluatedKey.get("key").getS(), "\u007F"));
        }
        List<String> list = new ArrayList<>(rowKeys);
        Collections.sort(list);
    	m_logger.debug("get rows in {} in {}", storeName, t);
        return list;
    }

    public static List<DColumn> fromItems(Iterable<Map<String, AttributeValue>> items) {
		List<DColumn> list = new ArrayList<>();
    	for(Map<String, AttributeValue> item: items) {
    		byte[] value = Utils.getBytes(item.get("value").getB());
    		if(value.length == 1 && value[0] == 0) value = new byte[0];
			list.add(new DColumn(item.get("column").getS(), value));
		}
    	return list;
    }
    
    public static Map<String, AttributeValue> getPrimaryKey(String key, String column) {
    	Map<String, AttributeValue> map = new HashMap<>();
    	map.put("key", new AttributeValue(key));
    	map.put("column", new AttributeValue(column));
    	return map;
    }

}
