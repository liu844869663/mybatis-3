/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.scripting.xmltags;

import java.util.regex.Pattern;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

/**
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {
  /**
   * 动态文本
   */
  private final String text;
  /**
   * 注入时的过滤器
   */
  private final Pattern injectionFilter;

  public TextSqlNode(String text) {
    this(text, null);
  }

  public TextSqlNode(String text, Pattern injectionFilter) {
    this.text = text;
    this.injectionFilter = injectionFilter;
  }

  public boolean isDynamic() {
    // <1> 创建 DynamicCheckerTokenParser 对象
    DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
    // <2> 创建 GenericTokenParser 对象
    GenericTokenParser parser = createParser(checker);
    // <3> 执行解析，如果存在 '${ }'，则 checker 会设置 isDynamic 为true
    parser.parse(text);
    // <4> 判断是否为动态文本
    return checker.isDynamic();
  }

  @Override
  public boolean apply(DynamicContext context) {
    // <1> 创建 BindingTokenParser 对象
    // <2> 创建 GenericTokenParser 对象
    GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
    // <3> 执行解析
    // <4> 将解析的结果，添加到 context 中
    context.appendSql(parser.parse(text));
    return true;
  }

  private GenericTokenParser createParser(TokenHandler handler) {
    return new GenericTokenParser("${", "}", handler);
  }

  private static class BindingTokenParser implements TokenHandler {

    private DynamicContext context;
    /**
     * 注入时的过滤器
     */
    private Pattern injectionFilter;

    public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
      this.context = context;
      this.injectionFilter = injectionFilter;
    }

    @Override
    public String handleToken(String content) {
      // 从上下文中获取入参对象，在DynamicContext的构造方法中可以看到为什么可以获取到
      Object parameter = context.getBindings().get("_parameter");
      if (parameter == null) {
        context.getBindings().put("value", null);
      } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
        context.getBindings().put("value", parameter);
      }
      // 使用 OGNL 表达式，获得对应的值
      Object value = OgnlCache.getValue(content, context.getBindings());
      String srtValue = value == null ? "" : String.valueOf(value); // issue #274 return "" instead of "null"
      // 使用过滤器进行过滤
      checkInjection(srtValue);
      return srtValue;
    }

    private void checkInjection(String value) {
      if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
        throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
      }
    }
  }

  private static class DynamicCheckerTokenParser implements TokenHandler {

    private boolean isDynamic;

    public DynamicCheckerTokenParser() {
      // Prevent Synthetic Access
    }

    public boolean isDynamic() {
      return isDynamic;
    }

    @Override
    public String handleToken(String content) {
      this.isDynamic = true;
      return null;
    }
  }

}
