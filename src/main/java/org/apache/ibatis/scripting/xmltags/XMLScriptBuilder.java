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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

	/**
	 * 当前 SQL 的 XNode 对象
	 */
	private final XNode context;
	/**
	 * 是否为动态 SQL
	 */
	private boolean isDynamic;
	/**
	 * SQL 的 Java 入参类型
	 */
	private final Class<?> parameterType;
	/**
	 * NodeHandler 的映射
	 */
	private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

	public XMLScriptBuilder(Configuration configuration, XNode context) {
		this(configuration, context, null);
	}

	public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
		super(configuration);
		this.context = context;
		this.parameterType = parameterType;
		initNodeHandlerMap();
	}

	private void initNodeHandlerMap() {
		nodeHandlerMap.put("trim", new TrimHandler());
		nodeHandlerMap.put("where", new WhereHandler());
		nodeHandlerMap.put("set", new SetHandler());
		nodeHandlerMap.put("foreach", new ForEachHandler());
		nodeHandlerMap.put("if", new IfHandler());
		nodeHandlerMap.put("choose", new ChooseHandler());
		nodeHandlerMap.put("when", new IfHandler());
		nodeHandlerMap.put("otherwise", new OtherwiseHandler());
		nodeHandlerMap.put("bind", new BindHandler());
	}

	public SqlSource parseScriptNode() {
	  // 解析 XML 或者注解中定义的 SQL
		MixedSqlNode rootSqlNode = parseDynamicTags(context);
		SqlSource sqlSource;
		if (isDynamic) {
		  // 动态语句，使用了 ${} 也算
			sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
		} else {
			sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
		}
		return sqlSource;
	}

	protected MixedSqlNode parseDynamicTags(XNode node) {
		// <1> 创建 SqlNode 数组
		List<SqlNode> contents = new ArrayList<>();
		/*
		 * <2> 遍历 SQL 节点中所有子节点
		 * 这里会对该节点内的所有内容进行处理然后返回 NodeList 对象
		 * 1. 文本内容会被解析成 '<#text></#text>' 节点，就算一个换行符也会解析成这个
		 * 2. <![CDATA[ content ]]> 会被解析成 '<#cdata-section>content</#cdata-section>' 节点
		 * 3. 还有其他MyBatis自定义的标签<if /> <where />
		 */
		NodeList children = node.getNode().getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			// 当前子节点
			XNode child = node.newXNode(children.item(i));
			// <2.1> 如果类型是 Node.CDATA_SECTION_NODE 或者 Node.TEXT_NODE 时
			if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE // <![CDATA[ ]]>节点
					|| child.getNode().getNodeType() == Node.TEXT_NODE) { // 纯文本
				// <2.1.1> 获得内容
				String data = child.getStringBody("");
				// <2.1.2> 创建 TextSqlNode 对象
				TextSqlNode textSqlNode = new TextSqlNode(data);
				if (textSqlNode.isDynamic()) { // <2.1.2.1> 如果是动态的 TextSqlNode 对象，也就是使用了 '${}'
					// 添加到 contents 中
					contents.add(textSqlNode);
					// 标记为动态 SQL
					isDynamic = true;
				} else { // <2.1.2.2> 如果是非动态的 TextSqlNode 对象，没有使用 '${}'
					// <2.1.2> 创建 StaticTextSqlNode 添加到 contents 中
					contents.add(new StaticTextSqlNode(data));
				}
			} else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628 <2.2> 如果类型是 Node.ELEMENT_NODE
				// <2.2.1> 根据子节点的标签，获得对应的 NodeHandler 对象
				String nodeName = child.getNode().getNodeName();
				NodeHandler handler = nodeHandlerMap.get(nodeName);
				if (handler == null) { // 获得不到，说明是未知的标签，抛出 BuilderException 异常
					throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
				}
				// <2.2.2> 执行 NodeHandler 处理
				handler.handleNode(child, contents);
				// <2.2.3> 标记为动态 SQL
				isDynamic = true;
			}
		}
    // <3> 创建 MixedSqlNode 对象
		return new MixedSqlNode(contents);
	}

	private interface NodeHandler {
		/**
		 * 处理 Node
		 *
		 * @param nodeToHandle   要处理的 XNode 节点
		 * @param targetContents 目标的 SqlNode 数组。实际上，被处理的 XNode 节点会创建成对应的 SqlNode 对象，添加到
		 *                       targetContents 中
		 */
		void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
	}

  /**
   * <bind />元素允许你在 OGNL 表达式(SQL语句)以外创建一个变量，并将其绑定到当前的上下文
   */
	private class BindHandler implements NodeHandler {
		public BindHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// 解析 name、value 属性
			final String name = nodeToHandle.getStringAttribute("name");
			final String expression = nodeToHandle.getStringAttribute("value");
			// 创建 VarDeclSqlNode 对象
			final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
			targetContents.add(node);
		}
	}

	private class TrimHandler implements NodeHandler {
		public TrimHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// <1> 解析内部的 SQL 节点，成 MixedSqlNode 对象
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			// <2> 获得 prefix、prefixOverrides、"suffix"、suffixOverrides 属性
			String prefix = nodeToHandle.getStringAttribute("prefix");
			String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
			String suffix = nodeToHandle.getStringAttribute("suffix");
			String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
			// <3> 创建 TrimSqlNode 对象
			TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
			targetContents.add(trim);
		}
	}

	private class WhereHandler implements NodeHandler {
		public WhereHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// 解析内部的 SQL 节点，成 MixedSqlNode 对象
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			// 创建 WhereSqlNode 对象
			WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
			targetContents.add(where);
		}
	}

	private class SetHandler implements NodeHandler {
		public SetHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// 解析内部的 SQL 节点，成 MixedSqlNode 对象
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
			targetContents.add(set);
		}
	}

	private class ForEachHandler implements NodeHandler {
		public ForEachHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// 解析内部的 SQL 节点，成 MixedSqlNode 对象
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			// 获得 collection、item、index、open、close、separator 属性
			String collection = nodeToHandle.getStringAttribute("collection");
			String item = nodeToHandle.getStringAttribute("item");
			String index = nodeToHandle.getStringAttribute("index");
			String open = nodeToHandle.getStringAttribute("open");
			String close = nodeToHandle.getStringAttribute("close");
			String separator = nodeToHandle.getStringAttribute("separator");
			// 创建 ForEachSqlNode 对象
			ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
			targetContents.add(forEachSqlNode);
		}
	}

	private class IfHandler implements NodeHandler {
		public IfHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// 解析内部的 SQL 节点，成 MixedSqlNode 对象
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			// 获得 test 属性
			String test = nodeToHandle.getStringAttribute("test");
			// 创建 IfSqlNode 对象
			IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
			targetContents.add(ifSqlNode);
		}
	}

	private class OtherwiseHandler implements NodeHandler {
		public OtherwiseHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			// 解析内部的 SQL 节点，成 MixedSqlNode 对象
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			targetContents.add(mixedSqlNode);
		}
	}

	private class ChooseHandler implements NodeHandler {
		public ChooseHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			List<SqlNode> whenSqlNodes = new ArrayList<>();
			List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
			// 解析 `<when />` 和 `<otherwise />` 的节点们
			handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
			// 获得 `<otherwise />` 的节点，存在多个会抛出异常
			SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
			// 创建 ChooseSqlNode 对象
			ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
			targetContents.add(chooseSqlNode);
		}

		private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes,
				List<SqlNode> defaultSqlNodes) {
			List<XNode> children = chooseSqlNode.getChildren();
			for (XNode child : children) {
				String nodeName = child.getNode().getNodeName();
				NodeHandler handler = nodeHandlerMap.get(nodeName);
				if (handler instanceof IfHandler) { // 处理 `<when />` 标签的情况
					handler.handleNode(child, ifSqlNodes);
				} else if (handler instanceof OtherwiseHandler) { // 处理 `<otherwise />` 标签的情况
					handler.handleNode(child, defaultSqlNodes);
				}
			}
		}

		private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
			SqlNode defaultSqlNode = null;
			if (defaultSqlNodes.size() == 1) {
				defaultSqlNode = defaultSqlNodes.get(0);
			} else if (defaultSqlNodes.size() > 1) {
				throw new BuilderException("Too many default (otherwise) elements in choose statement.");
			}
			return defaultSqlNode;
		}
	}

}
