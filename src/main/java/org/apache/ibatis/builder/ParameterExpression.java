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
package org.apache.ibatis.builder;

import java.util.HashMap;

/**
 * Inline parameter expression parser. Supported grammar (simplified):
 *
 * <pre>
 * inline-parameter = (propertyName | expression) oldJdbcType attributes
 * propertyName = /expression language's property navigation path/
 * expression = '(' /expression language's expression/ ')'
 * oldJdbcType = ':' /any valid jdbc type/
 * attributes = (',' attribute)*
 * attribute = name '=' value
 * </pre>
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class ParameterExpression extends HashMap<String, String> {

	private static final long serialVersionUID = -2417552199605158680L;

  /**
   * 从类的注释中可以看出我们可以这样定义占位符
   * 1. #{propertyName, javaType=string, jdbcType=VARCHAR}
   * 2. #{(expression), javaType=string, jdbcType=VARCHAR}
   *
   * @param expression 我们定义的占位符表达式
   */
	public ParameterExpression(String expression) {
		parse(expression);
	}

	private void parse(String expression) {
	  // 跳过前面的非法字符（ASCII 小于33），目的是去除空格，还有非法的字符，可以参照 ASCII 字符代码表看看
		int p = skipWS(expression, 0);
		if (expression.charAt(p) == '(') {
		  // 属于第二种方式，我在官方没有看到介绍，这里也不做介绍了
			expression(expression, p + 1);
		} else {
		  // 将整个字符串转换成 key-value 保存至 Map.Entry
			property(expression, p);
		}
	}

	private void expression(String expression, int left) {
		int match = 1;
		int right = left + 1;
		while (match > 0) {
			if (expression.charAt(right) == ')') {
				match--;
			} else if (expression.charAt(right) == '(') {
				match++;
			}
			right++;
		}
		// 获取表达式()中的内容，这样定义的属性采用了上面的第2种方式
		put("expression", expression.substring(left, right - 1));
		jdbcTypeOpt(expression, right);
	}

	private void property(String expression, int left) {
		if (left < expression.length()) {
		  // 获取到逗号或者冒号第一个位置，也就是分隔符
			int right = skipUntil(expression, left, ",:");
			// 从内容中截取第一个逗号前面的字符串，也上面第 1 种方式的 "name"
			put("property", trimmedStr(expression, left, right));
			// 解析字符串一个逗号后面的字符串，也就是该属性的相关配置
			jdbcTypeOpt(expression, right);
		}
	}

	private int skipWS(String expression, int p) {
		for (int i = p; i < expression.length(); i++) {
			if (expression.charAt(i) > 0x20) {
				return i;
			}
		}
		return expression.length();
	}

	private int skipUntil(String expression, int p, final String endChars) {
		for (int i = p; i < expression.length(); i++) {
			char c = expression.charAt(i);
			if (endChars.indexOf(c) > -1) {
				return i;
			}
		}
		return expression.length();
	}

	private void jdbcTypeOpt(String expression, int p) {
		p = skipWS(expression, p);
		if (p < expression.length()) {
			if (expression.charAt(p) == ':') { // 属于上面第 2 种方式，不做分析
				jdbcType(expression, p + 1);
			} else if (expression.charAt(p) == ',') {
			  // 将第一个 , 后面的字符串解析成 key-value 保存
				option(expression, p + 1);
			} else {
				throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
			}
		}
	}

	private void jdbcType(String expression, int p) {
		int left = skipWS(expression, p);
		int right = skipUntil(expression, left, ",");
		if (right > left) {
			put("jdbcType", trimmedStr(expression, left, right));
		} else {
			throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
		}
		option(expression, right + 1);
	}

  /**
   * 将字符串生成转换成key-value的形式
   * 例如 expression = "name, jdbcType = VARCHAR, javaType = string" 设置 p = 6
   * 这样将会往 Map 中保存两个键值对："jdbcType"->"VARCHAR" "javaType"->"string"
   *
   * @param expression 字符串
   * @param p 字符串从哪个位置转换
   */
	private void option(String expression, int p) {
		int left = skipWS(expression, p);
		if (left < expression.length()) {
		  // 获取 = 的位置
			int right = skipUntil(expression, left, "=");
			// 截取 = 前面的字符串，对应的 key
			String name = trimmedStr(expression, left, right);
			left = right + 1;
			// 获取 , 的位置
			right = skipUntil(expression, left, ",");
			// 截取 = 到 , 之间的字符串，也就是对应的 value
			String value = trimmedStr(expression, left, right);
			// 将 key-value 保存
			put(name, value);
			// 继续遍历后面的字符串
			option(expression, right + 1);
		}
	}

  /**
   * 截取字符串，如果存在非法字符则会忽略
   *
   * @param str   字符串
   * @param start 开始的位置
   * @param end   结束的位置
   * @return 截取到的字符串
   */
	private String trimmedStr(String str, int start, int end) {
		while (str.charAt(start) <= 0x20) {
			start++;
		}
		while (str.charAt(end - 1) <= 0x20) {
			end--;
		}
		return start >= end ? "" : str.substring(start, end);
	}

}
