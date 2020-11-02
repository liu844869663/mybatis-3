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
package org.apache.ibatis.scripting.xmltags;

import java.util.Collections;
import java.util.List;

import org.apache.ibatis.session.Configuration;

/**
 * <set />节点
 *
 * @author Clinton Begin
 */
public class SetSqlNode extends TrimSqlNode {

  /**
   * 也是通过 TrimSqlNode ，这里定义需要删除的前缀
   */
  private static final List<String> COMMA = Collections.singletonList(",");

  public SetSqlNode(Configuration configuration,SqlNode contents) {
    // 设置前缀、需要删除的前缀和后缀
    super(configuration, contents, "SET", COMMA, null, COMMA);
  }

}
