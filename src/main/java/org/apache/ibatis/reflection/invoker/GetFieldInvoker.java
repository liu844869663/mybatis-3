/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.lang.reflect.Field;

import org.apache.ibatis.reflection.Reflector;

/**
 * @author Clinton Begin
 */
public class GetFieldInvoker implements Invoker {
	/**
     * Field 对象
     */
	private final Field field;

	public GetFieldInvoker(Field field) {
		this.field = field;
	}

	
	@Override
	public Object invoke(Object target, Object[] args) throws IllegalAccessException {
		try {
			// 执行 target 对象中 field 字段的 getter 方法
			return field.get(target);
		} catch (IllegalAccessException e) { // 如果该字段为 private 修饰，则抛出异常
			// 进行校验，因为执行setAccessible(true)时内部会使用SecurityManager安全管理器进行check，不通过则抛出异常
			// 这里自己先进行相关校验，至于为何本人有待研究
			if (Reflector.canControlMemberAccessible()) {
				field.setAccessible(true);
				return field.get(target);
			} else {
				throw e;
			}
		}
	}

	@Override
	public Class<?> getType() {
		return field.getType();
	}
}
