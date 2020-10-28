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
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

	private final Configuration configuration;
	private final MapperBuilderAssistant builderAssistant;

	public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
		this.configuration = configuration;
		this.builderAssistant = builderAssistant;
	}

	public void applyIncludes(Node source) {
		// <1> 创建 variablesContext ，并将 configurationVariables 添加到其中
		Properties variablesContext = new Properties();
		Properties configurationVariables = configuration.getVariables();
		Optional.ofNullable(configurationVariables).ifPresent(variablesContext::putAll);
		// <2> 处理 <include />
		applyIncludes(source, variablesContext, false);
	}

	/**
	 * Recursively apply includes through all SQL fragments.
	 *
	 * @param source           Include node in DOM tree
	 * @param variablesContext Current context for static variables with values
	 */
	private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
		if (source.getNodeName().equals("include")) { // <1> 如果是 <include /> 标签
			// <1.1> 获得 <sql /> 对应的节点
			Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
			// <1.2> 获得包含 <include /> 标签内的属性
			Properties toIncludeContext = getVariablesContext(source, variablesContext);
			// <1.3> 递归调用 #applyIncludes(...) 方法，继续替换。注意，此处是 <sql /> 对应的节点
			applyIncludes(toInclude, toIncludeContext, true);
			if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
				toInclude = source.getOwnerDocument().importNode(toInclude, true);
			}
			// <1.4> 将 <include /> 节点替换成 <sql /> 节点
			source.getParentNode().replaceChild(toInclude, source); // 注意，这是一个奇葩的 API ，前者为 newNode ，后者为 oldNode
			// <1.4> 将 <sql /> 子节点添加到 <sql /> 节点前面
			while (toInclude.hasChildNodes()) {
				toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);; // 这里有个点，一定要注意，卡了艿艿很久。当子节点添加到其它节点下面后，这个子节点会不见了，相当于是“移动操作”
			}
			// <1.4> 移除 <include /> 标签自身
			toInclude.getParentNode().removeChild(toInclude);
		} else if (source.getNodeType() == Node.ELEMENT_NODE) { // <2> 如果节点类型为 Node.ELEMENT_NODE
			// <2.1> 如果在处理 <include /> 标签中，则替换其上的属性，进入以下逻辑的是 <sql />
      // 例如 <sql id="123" lang="${cpu}"> 的情况，lang 属性是可以被替换的
			if (included && !variablesContext.isEmpty()) {
				// replace variables in attribute values
				NamedNodeMap attributes = source.getAttributes();
				for (int i = 0; i < attributes.getLength(); i++) {
					Node attr = attributes.item(i);
					attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
				}
			}
			// <2.2> 遍历子节点，递归调用 #applyIncludes(...) 方法，继续替换
			NodeList children = source.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				applyIncludes(children.item(i), variablesContext, included);
			}
		// <3> 如果在处理 <include /> 标签中，并且节点类型为 Node.TEXT_NODE ，并且变量非空
		// 则进行变量的替换，并修改原节点 source
		} else if (included && (source.getNodeType() == Node.TEXT_NODE || source.getNodeType() == Node.CDATA_SECTION_NODE) && !variablesContext.isEmpty()) {
			// replace variables in text node
			source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
		}
	}

	private Node findSqlFragment(String refid, Properties variables) {
		// 因为 refid 可能是动态变量，所以进行替换
		refid = PropertyParser.parse(refid, variables);
		// 获得完整的 refid ，格式为 "${namespace}.${refid}"
		refid = builderAssistant.applyCurrentNamespace(refid, true);
		try {
			// 获得对应的 <sql /> 节点
			XNode nodeToInclude = configuration.getSqlFragments().get(refid);
			// 获得 Node 节点，进行克隆
			return nodeToInclude.getNode().cloneNode(true);
		} catch (IllegalArgumentException e) {
			throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'",
					e);
		}
	}

	private String getStringAttribute(Node node, String name) {
		return node.getAttributes().getNamedItem(name).getNodeValue();
	}

	/**
	 * Read placeholders and their values from include node definition.
	 *
	 * @param node                      Include node instance
	 * @param inheritedVariablesContext Current context used for replace variables
	 *                                  in new variables values
	 * @return variables context from include instance (no inherited values)
	 */
	private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
		 // 获得 <include /> 标签的属性集合
		Map<String, String> declaredProperties = null;
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node n = children.item(i);
			if (n.getNodeType() == Node.ELEMENT_NODE) {
				String name = getStringAttribute(n, "name");
				// Replace variables inside
				String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
				if (declaredProperties == null) {
					declaredProperties = new HashMap<>();
				}
				if (declaredProperties.put(name, value) != null) {
					throw new BuilderException("Variable " + name + " defined twice in the same include definition");
				}
			}
		}
		// 如果 <include /> 标签内没有属性，直接使用 inheritedVariablesContext 即可
		if (declaredProperties == null) {
			return inheritedVariablesContext;
		} else { // 如果 <include /> 标签内有属性，则创建新的 newProperties 集合，将 inheritedVariablesContext + declaredProperties 合并
			Properties newProperties = new Properties();
			newProperties.putAll(inheritedVariablesContext);
			newProperties.putAll(declaredProperties);
			return newProperties;
		}
	}
}
