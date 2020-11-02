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

import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.session.Configuration;

/**
 * <where />节点
 *
 * @author Clinton Begin
 */
public class WhereSqlNode extends TrimSqlNode {

  /**
   * 也是通过 TrimSqlNode ，这里定义需要删除的前缀
   */
	private static List<String> prefixList = Arrays.asList("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t");

	public WhereSqlNode(Configuration configuration, SqlNode contents) {
	  // 设置前缀和需要删除的前缀
		super(configuration, contents, "WHERE", prefixList, null, null);
	}

}
