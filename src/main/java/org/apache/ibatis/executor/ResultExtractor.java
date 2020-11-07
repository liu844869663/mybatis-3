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
package org.apache.ibatis.executor;

import java.lang.reflect.Array;
import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;

/**
 * @author Andrew Gustafson
 */
public class ResultExtractor {
  /**
   * 全局配置对象
   */
	private final Configuration configuration;
  /**
   * 实例工厂
   */
	private final ObjectFactory objectFactory;

	public ResultExtractor(Configuration configuration, ObjectFactory objectFactory) {
		this.configuration = configuration;
		this.objectFactory = objectFactory;
	}

	/**
     * 从 list 中，提取结果
     *
     * @param list list
     * @param targetType 结果类型
     * @return 结果
     */
	public Object extractObjectFromList(List<Object> list, Class<?> targetType) {
		Object value = null;
		/*
		 * 从查询结果中抽取数据转换成目标类型
		 */
		if (targetType != null && targetType.isAssignableFrom(list.getClass())) { // <1> 场景1，List 类型
		  // 直接返回
			value = list;
		} else if (targetType != null && objectFactory.isCollection(targetType)) { // <2> 场景2，集合类型
			// <2.1> 创建集合的实例对象
			value = objectFactory.create(targetType);
			// <2.2> 将结果添加到其中
			MetaObject metaObject = configuration.newMetaObject(value);
			// <2.3> 将查询结果全部添加到集合对象中
			metaObject.addAll(list);
		} else if (targetType != null && targetType.isArray()) { // <3> 场景3，数组类型
			// <3.1> 获取数组的成员类型
			Class<?> arrayComponentType = targetType.getComponentType();
			// <3.2> 创建数组对象，并设置大小
			Object array = Array.newInstance(arrayComponentType, list.size());
			if (arrayComponentType.isPrimitive()) { // <3.3> 如果是基本类型
				for (int i = 0; i < list.size(); i++) {
				  // 一个一个添加到数组中
					Array.set(array, i, list.get(i));
				}
				value = array;
			} else {
			  // <3.4> 将 List 转换成 Array
				value = list.toArray((Object[]) array);
			}
		} else { // <4> 场景4
			if (list != null && list.size() > 1) {
				throw new ExecutorException("Statement returned more than one row, where no more than one was expected.");
			} else if (list != null && list.size() == 1) {
			  // 取首个结果
				value = list.get(0);
			}
		}
		return value;
	}
}
