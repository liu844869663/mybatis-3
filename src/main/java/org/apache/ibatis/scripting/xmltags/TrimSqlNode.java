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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.ibatis.session.Configuration;

/**
 * <trim />节点
 *
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {

	/**
	 * MixedSqlNode，包含该<if />节点内所有信息
	 */
	private final SqlNode contents;
	/**
	 * 前缀，行首添加
	 */
	private final String prefix;
	/**
	 * 后缀，行尾添加
	 */
	private final String suffix;
	/**
	 * 需要删除的前缀，例如这样定义：'AND|OR'
   * 注意空格，这里是不会去除的
	 */
	private final List<String> prefixesToOverride;
	/**
	 * 需要删除的后缀，例如我们这样定义：',|AND'
   * 注意空格，这里是不会去除的
	 */
	private final List<String> suffixesToOverride;
	private final Configuration configuration;

	public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, String prefixesToOverride,
			String suffix, String suffixesToOverride) {
		this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix, parseOverrides(suffixesToOverride));
	}

	protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, List<String> prefixesToOverride,
			String suffix, List<String> suffixesToOverride) {
		this.contents = contents;
		this.prefix = prefix;
		this.prefixesToOverride = prefixesToOverride;
		this.suffix = suffix;
		this.suffixesToOverride = suffixesToOverride;
		this.configuration = configuration;
	}

	@Override
	public boolean apply(DynamicContext context) {
		// <1> 创建 FilteredDynamicContext 对象
		FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
		// <2> 先解析 <trim /> 节点中的内容，将生成的 SQL 先存放在 FilteredDynamicContext 中
		boolean result = contents.apply(filteredDynamicContext);
		/*
		 * <3> 执行 FilteredDynamicContext 的应用
		 * 对上一步解析到的内容进行处理
		 * 处理完成后再将处理后的 SQL 拼接到 DynamicContext 中
		 */
		filteredDynamicContext.applyAll();
		return result;
	}

	private static List<String> parseOverrides(String overrides) {
		if (overrides != null) {
			final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
			final List<String> list = new ArrayList<>(parser.countTokens());
			while (parser.hasMoreTokens()) {
				list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
			}
			return list;
		}
		return Collections.emptyList();
	}

	private class FilteredDynamicContext extends DynamicContext {
		/**
		 * 装饰的 DynamicContext 对象
		 */
		private DynamicContext delegate;
		/**
		 * 是否 prefix 已经被应用
		 */
		private boolean prefixApplied;
		/**
		 * 是否 suffix 已经被应用
		 */
		private boolean suffixApplied;
		/**
		 * StringBuilder 对象
		 *
		 * @see #appendSql(String)
		 */
		private StringBuilder sqlBuffer;

		public FilteredDynamicContext(DynamicContext delegate) {
			super(configuration, null);
			this.delegate = delegate;
			this.prefixApplied = false;
			this.suffixApplied = false;
			this.sqlBuffer = new StringBuilder();
		}

		public void applyAll() {
			// <1> 去除前后多余的空格，生成新的 sqlBuffer 对象
			sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
			// <2> 全部大写
			String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
			// <3> 应用 TrimSqlNode 的 trim 逻辑
			if (trimmedUppercaseSql.length() > 0) {
				applyPrefix(sqlBuffer, trimmedUppercaseSql);
				applySuffix(sqlBuffer, trimmedUppercaseSql);
			}
			delegate.appendSql(sqlBuffer.toString());
		}

		@Override
		public Map<String, Object> getBindings() {
			return delegate.getBindings();
		}

		@Override
		public void bind(String name, Object value) {
			delegate.bind(name, value);
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}

		@Override
		public void appendSql(String sql) {
			sqlBuffer.append(sql);
		}

		@Override
		public String getSql() {
			return delegate.getSql();
		}

		private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
			if (!prefixApplied) {
				prefixApplied = true;
				// prefixesToOverride 非空，先删除
				if (prefixesToOverride != null) {
					for (String toRemove : prefixesToOverride) {
						if (trimmedUppercaseSql.startsWith(toRemove)) {
							sql.delete(0, toRemove.trim().length());
							break;
						}
					}
				}
				// prefix 非空，再添加
				if (prefix != null) {
					sql.insert(0, " ");
					sql.insert(0, prefix);
				}
			}
		}

		private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
			if (!suffixApplied) {
				suffixApplied = true;
				// suffixesToOverride 非空，先删除
				if (suffixesToOverride != null) {
					for (String toRemove : suffixesToOverride) {
						if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
							int start = sql.length() - toRemove.trim().length();
							int end = sql.length();
							sql.delete(start, end);
							break;
						}
					}
				}
				// suffix 非空，再添加
				if (suffix != null) {
					sql.append(" ");
					sql.append(suffix);
				}
			}
		}

	}

}
