/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.suggest.completion;

import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.core.CompletionFieldMapper;
import org.elasticsearch.search.suggest.SuggestContextParser;
import org.elasticsearch.search.suggest.SuggestionSearchContext;
import org.elasticsearch.search.suggest.context.ContextMapping.ContextQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.search.suggest.SuggestUtils.parseSuggestContext;

/**
 *
 */
public class CompletionSuggestParser implements SuggestContextParser {

    private CompletionSuggester completionSuggester;
    private static final ParseField FUZZINESS = Fuzziness.FIELD.withDeprecation("edit_distance");

    public CompletionSuggestParser(CompletionSuggester completionSuggester) {
        this.completionSuggester = completionSuggester;
    }

    @Override
    public SuggestionSearchContext.SuggestionContext parse(XContentParser parser, MapperService mapperService) throws IOException {
        XContentParser.Token token;
        String fieldName = null;
        CompletionSuggestionContext suggestion = new CompletionSuggestionContext(completionSuggester);
        CompletionFieldMapper mapper = null;

        XContentParser contextParser = null;


        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                fieldName = parser.currentName();
            } else if (token.isValue()) {
                if (!parseSuggestContext(parser, mapperService, fieldName, suggestion))  {
                    if (token == XContentParser.Token.VALUE_BOOLEAN && "fuzzy".equals(fieldName)) {
                        suggestion.setFuzzy(parser.booleanValue());
                    }
                } else {
                    suggestion.mapper((CompletionFieldMapper)mapperService.smartNameFieldMapper(suggestion.getField()));
                    mapper = suggestion.mapper();
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("fuzzy".equals(fieldName)) {
                    suggestion.setFuzzy(true);
                    String fuzzyConfigName = null;
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            fuzzyConfigName = parser.currentName();
                        } else if (token.isValue()) {
                            if (FUZZINESS.match(fuzzyConfigName, ParseField.EMPTY_FLAGS)) {
                                suggestion.setFuzzyEditDistance(Fuzziness.parse(parser).asDistance());
                            } else if ("transpositions".equals(fuzzyConfigName)) {
                                suggestion.setFuzzyTranspositions(parser.booleanValue());
                            } else if ("min_length".equals(fuzzyConfigName) || "minLength".equals(fuzzyConfigName)) {
                                suggestion.setFuzzyMinLength(parser.intValue());
                            } else if ("prefix_length".equals(fuzzyConfigName) || "prefixLength".equals(fuzzyConfigName)) {
                                suggestion.setFuzzyPrefixLength(parser.intValue());
                            } else if ("unicode_aware".equals(fuzzyConfigName) || "unicodeAware".equals(fuzzyConfigName)) {
                                suggestion.setFuzzyUnicodeAware(parser.booleanValue());
                            }
                        }
                    }
                } else if ("context".equals(fieldName)) {
                    // Copy the current structure. We will parse, once the mapping is provided
                    XContentBuilder builder = XContentFactory.contentBuilder(parser.contentType());
                    builder.copyCurrentStructure(parser);
                    BytesReference bytes = builder.bytes();
                    contextParser = parser.contentType().xContent().createParser(bytes);
                } else {
                    throw new ElasticsearchIllegalArgumentException("suggester [completion] doesn't support field [" + fieldName + "]");
                }
            } else if("scalar".equals(fieldName)){
                if (mapper == null) {
                    throw new ElasticsearchIllegalArgumentException("suggester [completion] field[field] must come before field[scalar]");
                }
                Map<String,Integer> scalarMap = mapper.getScalarMap();
                int[] scalar = new int[scalarMap.size()];
                Arrays.fill(scalar, 0);
                if (token == XContentParser.Token.START_ARRAY) {
                    int index = 0;
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if (token == XContentParser.Token.VALUE_NUMBER) {
                            if (index >= scalar.length) {
                                throw new ElasticsearchIllegalArgumentException("suggester [completion] too many values in scalar, expected ["+ scalar.length+ "]");
                            }
                            scalar[index] = parser.intValue();
                        } else if (token == XContentParser.Token.VALUE_STRING) {
                            String scalarName = parser.text();
                            if (scalarMap.containsKey(scalarName)) {
                                scalar[scalarMap.get(scalarName)] = 1;
                            }
                        }
                        index++;
                    }
                    suggestion.setScalar(scalar);
                } else {
                    throw new ElasticsearchIllegalArgumentException("suggester[completion]  expected scalar to be an array");
                }

            } else {
                throw new ElasticsearchIllegalArgumentException("suggester[completion]  doesn't support field [" + fieldName + "]");
            }
        }

        if (mapper != null) {
            if (suggestion.getScalar() == null) {
                int[] scalar = new int[ mapper.getScalarMap().size()];
                Arrays.fill(scalar,1);
                suggestion.setScalar(scalar);
            }
            if (mapper.requiresContext()) {
                if (contextParser == null) {
                    throw new ElasticsearchIllegalArgumentException("suggester [completion] requires context to be setup");
                } else {
                    contextParser.nextToken();
                    List<ContextQuery> contextQueries = ContextQuery.parseQueries(mapper.getContextMapping(), contextParser);
                    suggestion.setContextQuery(contextQueries);
                }
            } else if (contextParser != null) {
                throw new ElasticsearchIllegalArgumentException("suggester [completion] doesn't expect any context");
            }
        }
        return suggestion;
    }

}
