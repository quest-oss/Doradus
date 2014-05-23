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

package com.dell.doradus.olap.aggregate;

public class MetricCollectorSum implements IMetricCollector {
	public long[] metric;
	@Override public boolean requiresConversion() { return false; }

	@Override public void setSize(int size) { metric = new long[size]; }
	@Override public int getSize() { return metric.length; }
	@Override public IMetricValue convert(IMetricValue value) { return value; }
	
	@Override public void add(int field, IMetricValue value) {
		MetricValueSum v = (MetricValueSum)value;
		metric[field] += v.metric;
	}

	@Override public IMetricValue get(int field) {
		MetricValueSum v = new MetricValueSum();
		if(field >= 0) v.metric = metric[field];
		return v;
	}

}
