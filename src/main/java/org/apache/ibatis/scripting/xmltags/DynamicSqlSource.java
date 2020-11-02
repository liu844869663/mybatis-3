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

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

	private final Configuration configuration;
	/**
	 * 根 SqlNode 对象
	 */
	private final SqlNode rootSqlNode;

	public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
		this.configuration = configuration;
		this.rootSqlNode = rootSqlNode;
	}

	@Override
	public BoundSql getBoundSql(Object parameterObject) {
		// <1> 创建本次解析的动态 SQL 语句的上下文
		DynamicContext context = new DynamicContext(configuration, parameterObject);
		// <2> 根据上下文应用整个 SqlNode
		rootSqlNode.apply(context);
		// <3> 创建 SqlSourceBuilder 对象
		SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
		Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    // <4> 通过 SqlSourceBuilder 将应用后的 SQL 进行转换，变量替换成 ? 占位符，并生成对应的 ParameterMapping 集合
		SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
		// <5> 创建 BoundSql 对象
		BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
		// <6> 添加附加参数到 BoundSql 对象中，因为上一步创建的`BoundSql`对象时候传入的仅是入参信息，没有添加附加参数
		context.getBindings().forEach(boundSql::setAdditionalParameter);
		return boundSql;
	}

}
