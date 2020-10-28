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

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  /**
   * Mapper 构造器小助手
   */
	private final MapperBuilderAssistant builderAssistant;
	/**
	 * 当前 XML 节点，例如：<select />、<insert />、<update />、<delete /> 标签
	 */
	private final XNode context;
	/**
	 * 要求的 databaseId
	 */
	private final String requiredDatabaseId;

	public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
		this(configuration, builderAssistant, context, null);
	}

	public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context,
			String databaseId) {
		super(configuration);
		this.builderAssistant = builderAssistant;
		this.context = context;
		this.requiredDatabaseId = databaseId;
	}

	public void parseStatementNode() {
		// <1> 获得 id 属性，编号。
		String id = context.getStringAttribute("id");
		// <2> 获得 databaseId ， 判断 databaseId 是否匹配
		String databaseId = context.getStringAttribute("databaseId");

		if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
			return;
		}

		// 获取该节点名称
		String nodeName = context.getNode().getNodeName();
		// 根据节点名称判断 SQL 类型
		SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
		// 是否为 Select 语句
		boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
		// 是否清空缓存
		boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
		// 是否使用缓存
		boolean useCache = context.getBooleanAttribute("useCache", isSelect);
		boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

		// Include Fragments before parsing
		XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
		// 将该节点的子节点 <include /> 转换成 <sql /> 节点
		includeParser.applyIncludes(context.getNode());

		// 获取参数类型名称
		String parameterType = context.getStringAttribute("parameterType");
		// 参数类型名称转换成 Java Type
		Class<?> parameterTypeClass = resolveClass(parameterType);

		// 获得 lang 对应的 LanguageDriver 对象
		String lang = context.getStringAttribute("lang");
		LanguageDriver langDriver = getLanguageDriver(lang);

		// Parse selectKey after includes and remove them.
    // 将该节点的子节点 <selectKey /> 解析成 SelectKeyGenerator 生成器
		processSelectKeyNodes(id, parameterTypeClass, langDriver);

		// Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
		KeyGenerator keyGenerator;
		String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
		keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    /*
     * 1. 如果上面存在 <selectKey /> 子节点，则获取上面对其解析后生成的 SelectKeyGenerator
     * 2. 否则判断该节点是否配置了 useGeneratedKeys 属性为 true 并且是 插入语句，则使用 Jdbc3KeyGenerator
     */
		if (configuration.hasKeyGenerator(keyStatementId)) {
			keyGenerator = configuration.getKeyGenerator(keyStatementId);
		} else {
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
        configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
        ? Jdbc3KeyGenerator.INSTANCE
        : NoKeyGenerator.INSTANCE;
		}

		// 创建对应的 SqlSource 对象，保存了该节点下 SQL 相关信息
		SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
		// 获得 Statement 类型，默认 PREPARED
		StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
		Integer fetchSize = context.getIntAttribute("fetchSize");
		Integer timeout = context.getIntAttribute("timeout");
		String parameterMap = context.getStringAttribute("parameterMap");
		// 获得返回结果类型名称
		String resultType = context.getStringAttribute("resultType");
		// 获取返回结果的 Java Type
		Class<?> resultTypeClass = resolveClass(resultType);
		// 获取 resultMap
		String resultMap = context.getStringAttribute("resultMap");
		String resultSetType = context.getStringAttribute("resultSetType");
		ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
		if (resultSetTypeEnum == null) {
			resultSetTypeEnum = configuration.getDefaultResultSetType();
		}
		// 对应的 java 属性，结合 useGeneratedKeys 使用
		String keyProperty = context.getStringAttribute("keyProperty");
    // 对应的 column 列名，结合 useGeneratedKeys 使用
		String keyColumn = context.getStringAttribute("keyColumn");
		String resultSets = context.getStringAttribute("resultSets");

		builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
				parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum, flushCache, useCache,
				resultOrdered, keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
	}

	private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
		// <1> 遍历该节点的 <selectKey /> 子节点
		List<XNode> selectKeyNodes = context.evalNodes("selectKey");
		if (configuration.getDatabaseId() != null) {
			parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
		}
		parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
		removeSelectKeyNodes(selectKeyNodes);
	}

	private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass,
			LanguageDriver langDriver, String skRequiredDatabaseId) {
		for (XNode nodeToHandle : list) {
			// <2> 获得完整 id ，格式为 `${parentId}${id}!selectKey`
			String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
			// <3> 获得 databaseId ， 判断 databaseId 是否匹配
			String databaseId = nodeToHandle.getStringAttribute("databaseId");
			if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
				// <4> 执行解析单个 <selectKey /> 节点
				parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
			}
		}
	}

	private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass,
			LanguageDriver langDriver, String databaseId) {
		// <1.1> 获得各种属性和对应的类
		String resultType = nodeToHandle.getStringAttribute("resultType");
		Class<?> resultTypeClass = resolveClass(resultType);
		StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
		String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
		String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
		// 是否在 SQL 语句执行之前执行该 SelectKey
		boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

		// defaults
		// <1.2> 创建 MappedStatement 需要用到的默认值
		boolean useCache = false;
		boolean resultOrdered = false;
		KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
		Integer fetchSize = null;
		Integer timeout = null;
		boolean flushCache = false;
		String parameterMap = null;
		String resultMap = null;
		ResultSetType resultSetTypeEnum = null;

		// <1.3> 创建 SqlSource 对象
		SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
		SqlCommandType sqlCommandType = SqlCommandType.SELECT;

		// <1.4> 创建 MappedStatement 对象
		builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
				parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum, flushCache, useCache,
				resultOrdered, keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

		// <2.1> 获得 SelectKeyGenerator 的编号，格式为 `${namespace}.${id}`
		id = builderAssistant.applyCurrentNamespace(id, false);

		// <2.2> 获得 MappedStatement 对象
		MappedStatement keyStatement = configuration.getMappedStatement(id, false);
		// <2.3> 创建 SelectKeyGenerator 对象，并添加到 Configuration 中
		configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
	}

	private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
		for (XNode nodeToHandle : selectKeyNodes) {
			nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
		}
	}

	private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
		if (requiredDatabaseId != null) {
			return requiredDatabaseId.equals(databaseId);
		}
		if (databaseId != null) {
			return false;
		}
		id = builderAssistant.applyCurrentNamespace(id, false);
		if (!this.configuration.hasStatement(id, false)) {
			return true;
		}
		// skip this statement if there is a previous one with a not null databaseId
		MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
		return previous.getDatabaseId() == null;
	}

	private LanguageDriver getLanguageDriver(String lang) {
		Class<? extends LanguageDriver> langClass = null;
		if (lang != null) {
			langClass = resolveClass(lang);
		}
		return configuration.getLanguageDriver(langClass);
	}

}
