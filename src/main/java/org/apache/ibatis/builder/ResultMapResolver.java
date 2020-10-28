/**
 *    Copyright 2009-2017 the original author or authors.
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

import java.util.List;

import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;

/**
 * @author Eduardo Macarron
 */
public class ResultMapResolver {
  /**
   * Mapper 构造器助手
   */
	private final MapperBuilderAssistant assistant;
	/**
	 * ResultMap 编号
	 */
	private final String id;
	/**
	 * 类型
	 */
	private final Class<?> type;
	/**
	 * 继承自哪个 ResultMap
	 */
	private final String extend;
	/**
	 * Discriminator 对象
	 */
	private final Discriminator discriminator;
	/**
	 * ResultMapping 集合
	 */
	private final List<ResultMapping> resultMappings;
	/**
	 * 是否自动匹配
	 */
	private final Boolean autoMapping;

	public ResultMapResolver(MapperBuilderAssistant assistant, String id, Class<?> type, String extend,
			Discriminator discriminator, List<ResultMapping> resultMappings, Boolean autoMapping) {
		this.assistant = assistant;
		this.id = id;
		this.type = type;
		this.extend = extend;
		this.discriminator = discriminator;
		this.resultMappings = resultMappings;
		this.autoMapping = autoMapping;
	}

  /**
   * 根据该 ResultMap 的相关信息进行解析
   * 通过 ResultMap 的 Builder 进行构建
   * 将 ResultMap 保存至 Configuration 全局配置
   *
   * @return ResultMap 对象
   */
	public ResultMap resolve() {
		return assistant.addResultMap(this.id, this.type, this.extend, this.discriminator, this.resultMappings, this.autoMapping);
	}

}
