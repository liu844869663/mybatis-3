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
package org.apache.ibatis.scripting.xmltags;

import java.util.List;

/**
 * 因为节点可能会嵌套很多个，所以会将一个 SQL 语句解析成一个 MixedSqlNode 对象
 *
 * @author Clinton Begin
 */
public class MixedSqlNode implements SqlNode {
  /**
   * 动态节点集合
   */
	private final List<SqlNode> contents;

	public MixedSqlNode(List<SqlNode> contents) {
		this.contents = contents;
	}

	@Override
	public boolean apply(DynamicContext context) {
	  // 逐个应用
		contents.forEach(node -> node.apply(context));
		return true;
	}
}
