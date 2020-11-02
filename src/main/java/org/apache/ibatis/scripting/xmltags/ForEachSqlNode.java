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

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  /**
   * 集合中元素绑定到上下文中 key 的前缀
   */
	public static final String ITEM_PREFIX = "__frch_";
	/**
	 * 表达式计算器
	 */
	private final ExpressionEvaluator evaluator;
  /**
   * 需要遍历的集合类型，支持：list set map array
   */
	private final String collectionExpression;
  /**
   * MixedSqlNode，包含该<where />节点内所有信息
   */
	private final SqlNode contents;
  /**
   * 开头
   */
	private final String open;
  /**
   * 结尾
   */
	private final String close;
  /**
   * 每个元素以什么分隔
   */
	private final String separator;
	/**
	 * 集合中每个元素的值
	 */
	private final String item;
	/**
	 * 集合中每个元素的索引
	 */
	private final String index;
	private final Configuration configuration;

	public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index,
			String item, String open, String close, String separator) {
		this.evaluator = new ExpressionEvaluator();
		this.collectionExpression = collectionExpression;
		this.contents = contents;
		this.open = open;
		this.close = close;
		this.separator = separator;
		this.index = index;
		this.item = item;
		this.configuration = configuration;
	}

	@Override
	public boolean apply(DynamicContext context) {
	  // 获取入参
		Map<String, Object> bindings = context.getBindings();
		/*
		 * <1> 获得遍历的集合的 Iterable 对象，用于遍历
		 * 例如配置了 collection 为以下类型
		 * list：则从入参中获取到 List 集合类型的属性的值
		 * array：则从入参中获取到 Array 数组类型的属性的值，会转换成 ArrayList
		 * map：则从入参中获取到 Map 集合类型的属性的值
		 */
		final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
		if (!iterable.iterator().hasNext()) {
		  // 集合中没有元素则无需遍历
			return true;
		}
		boolean first = true;
		// <2> 添加 open 到 SQL 中
		applyOpen(context);
		int i = 0;
		for (Object o : iterable) {
			// <3> 记录原始的 context 对象，下面通过两个装饰器对他进行操作
			DynamicContext oldContext = context;
			// <4> 生成一个 context 装饰器
			if (first || separator == null) {
				context = new PrefixedContext(context, "");
			} else {
        // 设置其需要添加的前缀为分隔符
				context = new PrefixedContext(context, separator);
			}
			// <5> 生成一个唯一索引值
			int uniqueNumber = context.getUniqueNumber();
			// Issue #709
			// <6> 绑定到 context 中
			if (o instanceof Map.Entry) {
				@SuppressWarnings("unchecked")
				Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        /*
         * 和下面同理，只不过索引是 Map 的 key
         */
				applyIndex(context, mapEntry.getKey(), uniqueNumber);
				applyItem(context, mapEntry.getValue(), uniqueNumber);
			} else {
        /*
         * 绑定当前集合中当前元素的索引到当前解析 SQL 语句的上下文中
         *
         * 1. 'index' -> i
         *
         * 2. __frch_'index'_uniqueNumber -> i
         */
				applyIndex(context, i, uniqueNumber);
				/*
				 * 绑定集合中当前元素的值到当前解析 SQL 语句的上下文中
				 *
				 * 1. 'item' -> o
				 *
				 * 2. __frch_'item'_uniqueNumber -> o
				 *
				 */
				applyItem(context, o, uniqueNumber);
			}
			/*
			 * 再装饰一下 PrefixedContext -> FilteredDynamicContext
			 *
			 * 前者进行前缀的添加，第一个元素添加后设置为已添加标记，后续不在添加
			 * 后者将<foreach />标签内的"#{item}"或者"#{index}"替换成上面我们已经绑定的数据"#{__frch_'item'_uniqueNumber}"或者"#{__frch_'index'_uniqueNumber}"
			 *
			 * <7> 进行转换，将<foreach />标签内部定义的内容进行转换
			 */
			contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
			if (first) { // <8> 判断 prefix 是否已经插入
				first = !((PrefixedContext) context).isPrefixApplied();
			}
			// <9> 恢复原始的 context 对象，因为目前 context 是装饰器
			context = oldContext;
			i++;
		}
		// <10> 添加 close 到 SQL 中
		applyClose(context);
		// <11> 移除 index 和 item 对应的绑定
		context.getBindings().remove(item);
		context.getBindings().remove(index);
		return true;
	}

	private void applyIndex(DynamicContext context, Object o, int i) {
		if (index != null) {
			context.bind(index, o);
			context.bind(itemizeItem(index, i), o);
		}
	}

	private void applyItem(DynamicContext context, Object o, int i) {
		if (item != null) {
			context.bind(item, o);
			context.bind(itemizeItem(item, i), o);
		}
	}

	private void applyOpen(DynamicContext context) {
		if (open != null) {
			context.appendSql(open);
		}
	}

	private void applyClose(DynamicContext context) {
		if (close != null) {
			context.appendSql(close);
		}
	}

	private static String itemizeItem(String item, int i) {
		return ITEM_PREFIX + item + "_" + i;
	}

	private static class FilteredDynamicContext extends DynamicContext {
    /**
     * 装饰的对象
     */
		private final DynamicContext delegate;
    /**
     * 集合中当前元素的索引
     */
		private final int index;
    /**
     * <foreach />定义的 index 属性
     */
		private final String itemIndex;
    /**
     * <foreach />定义的 item 属性
     */
		private final String item;

		public FilteredDynamicContext(Configuration configuration, DynamicContext delegate, String itemIndex,
				String item, int i) {
			super(configuration, null);
			this.delegate = delegate;
			this.index = i;
			this.itemIndex = itemIndex;
			this.item = item;
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
		public String getSql() {
			return delegate.getSql();
		}

		@Override
		public void appendSql(String sql) {
			GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
			  // 如果在`<foreach />`标签下的内容为通过item获取元素，则替换成`__frch_'item'_uniqueNumber`
				String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
				/*
				 * 如果在`<foreach />`标签中定义了index属性，并且标签下的内容为通过index获取元素
				 * 则替换成`__frch_'index'_uniqueNumber`
				 */
				if (itemIndex != null && newContent.equals(content)) {
					newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])",
							itemizeItem(itemIndex, index));
				}
				/*
				 * 返回`#{__frch_'item'_uniqueNumber}`或者`#{__frch_'index'_uniqueNumber}`
				 * 因为在前面已经将集合中的元素绑定在上下文的ContextMap中了，所以可以通过上面两个key获取到对应元素的值
				 * 例如绑定的数据：
				 * 1. __frch_'item'_uniqueNumber = 对应的元素值
				 * 2. __frch_'index'_uniqueNumber = 对应的元素值的索引
				 */
				return "#{" + newContent + "}";
			});

			delegate.appendSql(parser.parse(sql));
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}

	}

	private class PrefixedContext extends DynamicContext {
    /**
     * 装饰的 DynamicContext 对象
     */
		private final DynamicContext delegate;
    /**
     * 需要添加的前缀
     */
		private final String prefix;
    /**
     * 是否已经添加
     */
		private boolean prefixApplied;

		public PrefixedContext(DynamicContext delegate, String prefix) {
			super(configuration, null);
			this.delegate = delegate;
			this.prefix = prefix;
			this.prefixApplied = false;
		}

		public boolean isPrefixApplied() {
			return prefixApplied;
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
		public void appendSql(String sql) {
			if (!prefixApplied && sql != null && sql.trim().length() > 0) {
				delegate.appendSql(prefix);
				prefixApplied = true;
			}
			delegate.appendSql(sql);
		}

		@Override
		public String getSql() {
			return delegate.getSql();
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}
	}

}
