/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level
 * cache during a Session. Entries are sent to the cache when commit is called
 * or discarded if the Session is rolled back. Blocking cache support has been
 * added. Therefore any get() that returns a cache miss will be followed by a
 * put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

	private static final Log log = LogFactory.getLog(TransactionalCache.class);

	/**
	 * 委托的 Cache 对象。
	 *
	 * 实际上，就是二级缓存 Cache 对象。
	 */
	private final Cache delegate;
	/**
	 * 提交时，清空 {@link #delegate}
	 *
	 * 初始时，该值为 false
	 * 清理后{@link #clear()} 时，该值为 true ，表示持续处于清空状态
   *
   * 因为可能事务还未提交，所以不能直接清空所有的缓存，而是设置一个标记，获取缓存的时候返回 null 即可
   * 先清空下面这个待提交变量，待事务提交的时候才真正的清空缓存
   *
	 */
	private boolean clearOnCommit;
	/**
	 * 待提交的 Key-Value 映射
	 */
	private final Map<Object, Object> entriesToAddOnCommit;
	/**
	 * 查找不到的 KEY 集合
	 */
	private final Set<Object> entriesMissedInCache;

	public TransactionalCache(Cache delegate) {
		this.delegate = delegate;
		this.clearOnCommit = false;
		this.entriesToAddOnCommit = new HashMap<>();
		this.entriesMissedInCache = new HashSet<>();
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}

	@Override
	public Object getObject(Object key) {
		// issue #116
		// <1> 从 delegate 中获取 key 对应的 value
		Object object = delegate.getObject(key);
		if (object == null) {// <2> 如果不存在，则添加到 entriesMissedInCache 中
			entriesMissedInCache.add(key);
		}
		// issue #146
		if (clearOnCommit) {// <3> 如果 clearOnCommit 为 true ，表示处于持续清空状态，则返回 null
			return null;
		} else {
			return object;
		}
	}

	@Override
	public void putObject(Object key, Object object) {
		// 暂存 KV 到 entriesToAddOnCommit 中
		entriesToAddOnCommit.put(key, object);
	}

	@Override
	public Object removeObject(Object key) {
		return null;
	}

	@Override
	public void clear() {
    // <1> 标记 clearOnCommit 为 true
		clearOnCommit = true;
    // <2> 清空 entriesToAddOnCommit
		entriesToAddOnCommit.clear();
	}

	public void commit() {
		// <1> 如果 clearOnCommit 为 true ，则清空 delegate 缓存
		if (clearOnCommit) {
			delegate.clear();
		}
		// 将 entriesToAddOnCommit、entriesMissedInCache 刷入 delegate 中
		flushPendingEntries();
		// 重置
		reset();
	}

	public void rollback() {
		// <1> 从 delegate 移除出 entriesMissedInCache
		unlockMissedEntries();
		// <2> 重置
		reset();
	}

	private void reset() {
		clearOnCommit = false;
		entriesToAddOnCommit.clear();
		entriesMissedInCache.clear();
	}

	private void flushPendingEntries() {
		for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
			delegate.putObject(entry.getKey(), entry.getValue());
		}
		for (Object entry : entriesMissedInCache) {
			if (!entriesToAddOnCommit.containsKey(entry)) {
				delegate.putObject(entry, null);
			}
		}
	}

	private void unlockMissedEntries() {
		for (Object entry : entriesMissedInCache) {
			try {
				delegate.removeObject(entry);
			} catch (Exception e) {
				log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
						+ "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
			}
		}
	}

}
