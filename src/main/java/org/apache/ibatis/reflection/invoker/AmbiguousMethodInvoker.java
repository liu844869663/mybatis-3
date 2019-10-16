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

package org.apache.ibatis.reflection.invoker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.ibatis.reflection.ReflectionException;

/**
 * 
 * 如果一个字段对应多个setter方法或者getter方法 
 * 并且无法在多个方法中选择出最优的setter方法或者getter方法
 * 则从多个中取（随机）一个作为setter方法或者getter方法 
 * 然后创建AmbiguousMethodInvoker对象，其中包含异常信息
 * 
 * @author jingping.liu
 * @date 2019-10-16
 *
 */
public class AmbiguousMethodInvoker extends MethodInvoker {
	/**
	 * 表明该方法为何模棱两可
	 * 例如有个字段为private Object name，有两个setter方法setName(String name)、setName(Integer name)
	 * Ambiguous setters defined for property 'name' in class 'org.apache.ibatis.reflection.example' 
	 * with types 'java.lang.String' and 'java.lang.Integer'.
	 */
	private final String exceptionMessage;

	public AmbiguousMethodInvoker(Method method, String exceptionMessage) {
		super(method);
		this.exceptionMessage = exceptionMessage;
	}

	@Override
	public Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException {
		throw new ReflectionException(exceptionMessage);
	}
}
