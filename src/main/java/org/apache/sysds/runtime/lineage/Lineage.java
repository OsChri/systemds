/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.lineage;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.ForProgramBlock;
import org.apache.sysds.runtime.controlprogram.ProgramBlock;
import org.apache.sysds.runtime.controlprogram.WhileProgramBlock;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.ReuseCacheType;

import java.util.HashMap;
import java.util.Map;

import static org.apache.sysds.utils.Explain.explain;

public class Lineage {
	//thread-/function-local lineage DAG
	private final LineageMap _map;
	
	//optional deduplication blocks (block := map of lineage patches per loop/function)
	//(for invalid loops, there is a null entry to avoid redundant validity checks)
	private final Map<ProgramBlock, LineageDedupBlock> _dedupBlocks = new HashMap<>();
	private LineageDedupBlock _activeDedupBlock = null; //used during dedup runtime
	private LineageDedupBlock _initDedupBlock = null;   //used during dedup init
	
	public Lineage() {
		_map = new LineageMap();
	}
	
	public Lineage(Lineage that) {
		_map = new LineageMap(that._map);
	}
	
	public void trace(Instruction inst, ExecutionContext ec) {
		if (_activeDedupBlock == null)
			_map.trace(inst, ec);
	}
	
	public void traceCurrentDedupPath() {
		if( _activeDedupBlock != null ) {
			long lpath = _activeDedupBlock.getPath();
			LineageMap lm = _activeDedupBlock.getMap(lpath);
			if (lm != null)
				_map.processDedupItem(lm, lpath);
			
		}
	}
	
	public LineageItem getOrCreate(CPOperand variable) {
		return _initDedupBlock == null ?
			_map.getOrCreate(variable) :
			_initDedupBlock.getActiveMap().getOrCreate(variable);
	}
	
	public boolean contains(CPOperand variable) {
		return _initDedupBlock == null ?
			_map.containsKey(variable.getName()) :
			_initDedupBlock.getActiveMap().containsKey(variable.getName());
	}
	
	public LineageItem get(String varName) {
		return _map.get(varName);
	}
	
	public void set(String varName, LineageItem li) {
		_map.set(varName, li);
	}
	
	public void setLiteral(String varName, LineageItem li) {
		_map.setLiteral(varName, li);
	}
	
	public LineageItem get(CPOperand variable) {
		return _initDedupBlock == null ?
			_map.get(variable) :
			_initDedupBlock.getActiveMap().get(variable);
	}
	
	public void resetDedupPath() {
		if( _activeDedupBlock != null )
			_activeDedupBlock.resetPath();
	}
	
	public void setDedupPathBranch(int pos, boolean value) {
		if( _activeDedupBlock != null && value )
			_activeDedupBlock.setPathBranch(pos, value);
	}
	
	public void setInitDedupBlock(LineageDedupBlock ldb) {
		_initDedupBlock = ldb;
	}
	
	public void computeDedupBlock(ProgramBlock pb, ExecutionContext ec) {
		if( !(pb instanceof ForProgramBlock || pb instanceof WhileProgramBlock) )
			throw new DMLRuntimeException("Invalid deduplication block: "+ pb.getClass().getSimpleName());
		if (!_dedupBlocks.containsKey(pb)) {
			boolean valid = LineageDedupUtils.isValidDedupBlock(pb, false);
			_dedupBlocks.put(pb, valid?
				LineageDedupUtils.computeDedupBlock(pb, ec) : null);
		}
		_activeDedupBlock = _dedupBlocks.get(pb); //null if invalid
	}
	
	public void clearDedupBlock() {
		_activeDedupBlock = null;
	}
	
	public Map<String,String> serialize() {
		Map<String,String> ret = new HashMap<>();
		for (Map.Entry<String,LineageItem> e : _map.getTraces().entrySet()) {
			ret.put(e.getKey(), explain(e.getValue()));
		}
		return ret;
	}
	
	public static Lineage deserialize(Map<String,String> serialLineage) {
		Lineage ret = new Lineage();
		for (Map.Entry<String,String> e : serialLineage.entrySet()) {
			ret.set(e.getKey(), LineageParser.parseLineageTrace(e.getValue()));
		}
		return ret;
	}
	
	public static void resetInternalState() {
		LineageItem.resetIDSequence();
		LineageCache.resetCache();
		LineageCacheStatistics.reset();
	}
	
	public static void setLinReusePartial() {
		LineageCacheConfig.setConfigTsmmCbind(ReuseCacheType.REUSE_PARTIAL);
	}

	public static void setLinReuseFull() {
		LineageCacheConfig.setConfigTsmmCbind(ReuseCacheType.REUSE_FULL);
	}
	
	public static void setLinReuseFullAndPartial() {
		LineageCacheConfig.setConfigTsmmCbind(ReuseCacheType.REUSE_HYBRID);
	}

	public static void setLinReuseNone() {
		LineageCacheConfig.setConfigTsmmCbind(ReuseCacheType.NONE);
	}
}
