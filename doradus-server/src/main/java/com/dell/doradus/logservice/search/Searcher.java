package com.dell.doradus.logservice.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.FieldDefinition;
import com.dell.doradus.common.FieldType;
import com.dell.doradus.common.TableDefinition;
import com.dell.doradus.logservice.ChunkInfo;
import com.dell.doradus.logservice.ChunkReader;
import com.dell.doradus.logservice.LogAggregate;
import com.dell.doradus.logservice.LogEntry;
import com.dell.doradus.logservice.LogQuery;
import com.dell.doradus.logservice.LogService;
import com.dell.doradus.logservice.SyntheticFields;
import com.dell.doradus.logservice.search.filter.FilterBuilder;
import com.dell.doradus.logservice.search.filter.IFilter;
import com.dell.doradus.olap.aggregate.AggregationResult;
import com.dell.doradus.olap.io.BSTR;
import com.dell.doradus.olap.store.BitVector;
import com.dell.doradus.search.SearchResultList;
import com.dell.doradus.search.aggregate.AggregationGroup;
import com.dell.doradus.search.parser.DoradusQueryBuilder;
import com.dell.doradus.search.query.Query;
import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DColumn;
import com.dell.doradus.service.db.Tenant;

public class Searcher {
    
    public static SearchResultList search(LogService ls, Tenant tenant, String application, String table, LogQuery logQuery) {
        SearchRequest request = new SearchRequest(tenant, application, table, logQuery);
        SearchCollector collector = new SearchCollector(request.getCount());
        LogEntry current = null;
        List<String> partitions = ls.getPartitions(tenant, application, table, request.getMinTimestamp(), request.getMaxTimestamp());
        //optimization: inverse partitions
        if(request.getSkipCount() && request.getSortDescending()) {
            Collections.reverse(partitions);
        }
        IFilter filter = FilterBuilder.build(request.getQuery());
        ChunkReader chunkReader = new ChunkReader();
        if(logQuery.getPattern() != null) chunkReader.setSyntheticFields(logQuery.getPattern());
        
        int documentsCount = 0;
        for(String partition: partitions) {
            long minPartitionTimestamp = ls.getTimestamp(partition);
            long maxPartitionTimestamp = minPartitionTimestamp + 1000 * 3600 * 24;
            if(!checkInRange(minPartitionTimestamp, maxPartitionTimestamp, request, collector)) continue;
            Iterable<ChunkInfo> chunks = ls.getChunks(tenant, application, table, partition);
            chunks = new SortedChunkIterable(chunks, request.getSortDescending());
            for(ChunkInfo chunkInfo: chunks) {
                if(!checkInRange(chunkInfo.getMinTimestamp(), chunkInfo.getMaxTimestamp(), request, collector)) continue;
                int c = filter.check(chunkInfo);
                if(c == -1) continue;
                BitVector bv = new BitVector(chunkInfo.getEventsCount());
                ls.readChunk(tenant, application, table, chunkInfo, chunkReader);
                if(c == 1) bv.setAll();
                else filter.check(chunkReader, bv);
                
                if(request.getSortDescending()) {
                    for(int i = chunkReader.size() - 1; i >= 0; i--) {
                        if(!bv.get(i)) continue;
                        long timestamp = chunkReader.getTimestamp(i);
                        if(timestamp < request.getMinTimestamp()) continue;
                        if(timestamp >= request.getMaxTimestamp()) continue;
                        //optimization: avoid instantiating LogEntry if it won't go to the results
                        if(collector.size() < request.getCount() || timestamp > collector.getMinTimestamp()) {
                            if(current == null) current = new LogEntry(request.getFields(), request.getSortDescending());
                            current.set(chunkReader, i);
                            current = collector.add(current);
                        }
                        documentsCount++;
                    }
                } else {
                    for(int i = 0; i < chunkReader.size(); i++) {
                        if(!bv.get(i)) continue;
                        long timestamp = chunkReader.getTimestamp(i);
                        if(timestamp < request.getMinTimestamp()) continue;
                        if(timestamp >= request.getMaxTimestamp()) continue;
                        //optimization: avoid instantiating LogEntry if it won't go to the results
                        if(collector.size() < request.getCount() || timestamp < collector.getMaxTimestamp()) {
                            if(current == null) current = new LogEntry(request.getFields(), request.getSortDescending());
                            current.set(chunkReader, i);
                            current = collector.add(current);
                        }
                        documentsCount++;
                    }
                }
            }
        }
        
        SearchResultList list = collector.getSearchResult(request.getFieldSet(), request.getSortOrders());
        if(!request.getSkipCount()) list.documentsCount = documentsCount;
        if(list.results.size() == request.getCount()) list.continuation_token = list.results.get(list.results.size() - 1).id();
        if(request.getSkip() > 0) {
            int size = list.results.size();
            if(request.getSkip() >= size) list.results.clear();
            else list.results = new ArrayList<>(list.results.subList(request.getSkip(), size));
        }
        return list;
    }
    
    private static boolean checkInRange(long minTimestamp, long maxTimestamp, SearchRequest request, SearchCollector collector) {
        if(maxTimestamp < request.getMinTimestamp()) return false;
        if(minTimestamp >= request.getMaxTimestamp()) return false;
        if(request.getSkipCount() && collector.size() >= request.getCount()) {
            if(request.getSortDescending()) {
                if(maxTimestamp <= collector.getMinTimestamp()) return false;
            } else {
                if(minTimestamp >= collector.getMaxTimestamp()) return false;
            }
        }
        return true;
    }
    
    
    public static AggregationResult aggregate(LogService ls, Tenant tenant, String application, String table, LogAggregate logAggregate) {
        TableDefinition tableDef = Searcher.getTableDef(tenant, application, table, logAggregate.getPattern());
        Query query = DoradusQueryBuilder.Build(logAggregate.getQuery(), tableDef);
        AggregationGroup group = Aggregate.getAggregationGroup(tableDef, logAggregate.getFields());
        String field = Aggregate.getAggregateField(group);
        IFilter filter = FilterBuilder.build(query);
        AggregateCollector collector = null;
        if(group != null && group.batchexFilters != null) {
            collector = new AggregateCollectorSets(filter, group.batchexFilters, group.batchexAliases);
        }
        else if(field == null) {
            collector = new AggregateCollectorNoField(filter);
        }
        else if("Timestamp".equals(field)) {
            collector = new AggregateCollectorTimestamp(filter, group.truncate, group.timeZone); 
        }
        else {
            collector = new AggregateCollectorField(filter, field);
        }
        collector.setContext(ls, tenant, application, table, logAggregate.getPattern());
        
        List<String> partitions = ls.getPartitions(tenant, application, table);
        for(String partition: partitions) {
            for(ChunkInfo chunkInfo: ls.getChunks(tenant, application, table, partition)) {
                collector.addChunk(chunkInfo);
            }
        }
            
        AggregationResult result = collector.getResult();

        if(group != null) {
            Comparator<AggregationResult.AggregationGroup> comparer = AggregationGroupComparator.getComparator(group);
            Collections.sort(result.groups, comparer);
            if(group.selectionValue > 0 && group.selectionValue < result.groups.size()) {
                result.groups = new ArrayList<>(result.groups.subList(0, group.selectionValue));
            }
        }
        
        return result;
    }
    
    
    
    public static TableDefinition getTableDef(Tenant tenant, String application, String table, String pattern) {
        String store = application + "_" + table;
        ApplicationDefinition appDef = new ApplicationDefinition();
        appDef.setAppName(application);
        TableDefinition tableDef = new TableDefinition(appDef, table);
        appDef.addTable(tableDef);
        addField(tableDef, "Timestamp", FieldType.TIMESTAMP);
        for(DColumn c: DBService.instance(tenant).getAllColumns(store, "fields")) {
            addField(tableDef, c.getName());
        }
        
        if(pattern != null) {
            SyntheticFields synth = new SyntheticFields(pattern);
            addField(tableDef, synth.getBaseFieldName().toString());
            for(BSTR field: synth.getFieldNames()) {
                addField(tableDef, field.toString());
            }
        }
        return tableDef;
    }
    
    private static FieldDefinition addField(TableDefinition tableDef, String field) {
        return addField(tableDef, field, FieldType.TEXT);
    }
    
    private static FieldDefinition addField(TableDefinition tableDef, String field, FieldType type) {
        FieldDefinition fieldDef = tableDef.getFieldDef(field);
        if(fieldDef != null) return fieldDef;
        fieldDef = new FieldDefinition(tableDef);
        fieldDef.setType(type);
        fieldDef.setName(field);
        tableDef.addFieldDefinition(fieldDef);
        return fieldDef;
    }
    
}
