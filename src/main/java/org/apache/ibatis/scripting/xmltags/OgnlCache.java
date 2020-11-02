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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ognl.Ognl;
import ognl.OgnlException;

import org.apache.ibatis.builder.BuilderException;

/**
 * Caches OGNL parsed expressions.
 *
 * @author Eduardo Macarron
 *
 * @see <a href='http://code.google.com/p/mybatis/issues/detail?id=342'>Issue
 *      342</a>
 */
public final class OgnlCache {

	/**
	 * OgnlMemberAccess 单例，用于修改某个对象的成员为可访问
	 */
	private static final OgnlMemberAccess MEMBER_ACCESS = new OgnlMemberAccess();
	/**
	 * OgnlClassResolver 单例，用于创建 Class 对象
	 */
	private static final OgnlClassResolver CLASS_RESOLVER = new OgnlClassResolver();
	/**
	 * 表达式的缓存的映射
	 *
	 * KEY：表达式 VALUE：表达式的缓存 @see #parseExpression(String)
	 */
	private static final Map<String, Object> expressionCache = new ConcurrentHashMap<>();

	private OgnlCache() {
		// Prevent Instantiation of Static Class
	}

	public static Object getValue(String expression, Object root) {
		try {
      /*
       * <1> 创建 OgnlContext 对象，设置 OGNL 的成员访问器和类解析器，设置根元素为 root 对象
       * 这里是调用 OgnlContext 的s etRoot 方法直接设置根元素，可以通过 'user.id' 获取结果
       * 如果是通过 put 方法添加的对象，则取值时需要使用'#'，例如 '#user.id'
       */
      Map context = Ognl.createDefaultContext(root, MEMBER_ACCESS, CLASS_RESOLVER, null);
      /*
       * <2> expression 转换成 Ognl 表达式
       * <3> 根据 Ognl 表达式获取结果
       */
			return Ognl.getValue(parseExpression(expression), context, root);
		} catch (OgnlException e) {
			throw new BuilderException("Error evaluating expression '" + expression + "'. Cause: " + e, e);
		}
	}

  /**
   * 根据表达式构建一个 Ognl 表达式
   *
   * @param expression 表达式，例如<if test="user.id &gt; 0"> </if>，那这里传入的就是 "user.id &gt; 0"
   * @return Ognl 表达式
   * @throws OgnlException 异常
   */
  private static Object parseExpression(String expression) throws OgnlException {
    Object node = expressionCache.get(expression);
    if (node == null) {
      node = Ognl.parseExpression(expression);
      expressionCache.put(expression, node);
    }
    return node;
  }

}
