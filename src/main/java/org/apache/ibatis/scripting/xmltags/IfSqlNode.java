/**
 *    Copyright 2009-2017 the original author or authors.
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
 * <if />节点
 *
 * @author Clinton Begin
 */
public class IfSqlNode implements SqlNode {
  /**
   * 表达式计算器
   */
	private final ExpressionEvaluator evaluator;
  /**
   * 判断条件的表达式
   */
	private final String test;
  /**
   * MixedSqlNode，包含该<if />节点内所有信息
   */
	private final SqlNode contents;

	public IfSqlNode(SqlNode contents, String test) {
		this.test = test;
		this.contents = contents;
		this.evaluator = new ExpressionEvaluator();
	}

	@Override
	public boolean apply(DynamicContext context) {
		// <1> 判断是否符合条件
		if (evaluator.evaluateBoolean(test, context.getBindings())) {
			// <2> 解析该<if />节点中的内容
			contents.apply(context);
			return true;
		}
		// <3> 不符合
		return false;
	}

}
