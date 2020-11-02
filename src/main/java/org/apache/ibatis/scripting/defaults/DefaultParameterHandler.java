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
package org.apache.ibatis.scripting.defaults;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

	private final TypeHandlerRegistry typeHandlerRegistry;

  /**
   * MappedStatement 对象
   */
	private final MappedStatement mappedStatement;
  /**
   * 入参
   */
	private final Object parameterObject;
  /**
   * BoundSql 对象，实际的 SQL 语句
   */
	private final BoundSql boundSql;
  /**
   * 全局配置对象
   */
	private final Configuration configuration;

	public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
		this.mappedStatement = mappedStatement;
		this.configuration = mappedStatement.getConfiguration();
		this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
		this.parameterObject = parameterObject;
		this.boundSql = boundSql;
	}

	@Override
	public Object getParameterObject() {
		return parameterObject;
	}

	@Override
	public void setParameters(PreparedStatement ps) {
		ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
		// 获取 SQL 的参数信息 ParameterMapping 对象
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		if (parameterMappings != null) {
			// 遍历所有参数
			for (int i = 0; i < parameterMappings.size(); i++) {
				ParameterMapping parameterMapping = parameterMappings.get(i);
        /*
         * OUT 表示参数仅作为出参，非 OUT 也就是需要作为入参
         */
				if (parameterMapping.getMode() != ParameterMode.OUT) {
					Object value;
					// 获取入参的属性名
					String propertyName = parameterMapping.getProperty();
					/*
					 * 获取入参的实际值
					 */
					if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
					  // 在附加参数集合（<bind />标签生成的）中获取
						value = boundSql.getAdditionalParameter(propertyName);
					} else if (parameterObject == null) {
					  // 入参为 null 则该属性也定义为 null
						value = null;
					} else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
					  // 有类型处理器，则直接获取入参对象
						value = parameterObject;
					} else {
					  // 创建入参对应的 MetaObject 对象并获取该属性的值
						MetaObject metaObject = configuration.newMetaObject(parameterObject);
						value = metaObject.getValue(propertyName);
					}
					// 获取定义的参数类型处理器
					TypeHandler typeHandler = parameterMapping.getTypeHandler();
					// 获取定义的 Jdbc Type
					JdbcType jdbcType = parameterMapping.getJdbcType();
					if (value == null && jdbcType == null) {
						// 如果没有则设置成 'OTHER'
						jdbcType = configuration.getJdbcTypeForNull();
					}
					try {
						// 通过定义的 TypeHandler 参数类型处理器将 value 设置到对应的占位符
						typeHandler.setParameter(ps, i + 1, value, jdbcType);
					} catch (TypeException | SQLException e) {
						throw new TypeException(
								"Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
					}
				}
			}
		}
	}

}
