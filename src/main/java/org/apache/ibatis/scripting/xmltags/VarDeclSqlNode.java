/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

/**
 * <bind />节点，允许你在 OGNL 表达式(SQL语句)以外创建一个变量，并将其绑定到当前的上下文
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class VarDeclSqlNode implements SqlNode {

  /**
   * 变量名称
   */
	private final String name;
  /**
   * 表达式
   */
	private final String expression;

	public VarDeclSqlNode(String var, String exp) {
		name = var;
		expression = exp;
	}

	@Override
	public boolean apply(DynamicContext context) {
	  // 获取该表达式转换后结果
		final Object value = OgnlCache.getValue(expression, context.getBindings());
		// 将该结果与变量名称设置到解析 SQL 语句的上下文中，这样接下来的解析过程中可以获取到 name 的值
		context.bind(name, value);
		return true;
	}

}
