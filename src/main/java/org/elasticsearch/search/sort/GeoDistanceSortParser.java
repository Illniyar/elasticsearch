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

package org.elasticsearch.search.sort;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.FieldCache.Doubles;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.FixedBitSet;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.geo.GeoDistance.FixedSourceDistance;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.*;
import org.elasticsearch.index.fielddata.IndexFieldData.XFieldComparatorSource.Nested;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.ObjectMappers;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.index.query.ParsedFilter;
import org.elasticsearch.index.search.nested.NonNestedDocsFilter;
import org.elasticsearch.search.MultiValueMode;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;

/**
 *
 */
public class GeoDistanceSortParser implements SortParser {

    @Override
    public String[] names() {
        return new String[]{"_geo_distance", "_geoDistance"};
    }

    @Override
    public SortField parse(XContentParser parser, SearchContext context) throws Exception {
        String fieldName = null;
        GeoPoint point = new GeoPoint();
        DistanceUnit unit = DistanceUnit.DEFAULT;
        GeoDistance geoDistance = GeoDistance.DEFAULT;
        boolean reverse = false;
        MultiValueMode sortMode = null;
        String nestedPath = null;
        Filter nestedFilter = null;

        boolean normalizeLon = true;
        boolean normalizeLat = true;

        XContentParser.Token token;
        String currentName = parser.currentName();
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                GeoUtils.parseGeoPoint(parser, point);
                fieldName = currentName;
            } else if (token == XContentParser.Token.START_OBJECT) {
                // the json in the format of -> field : { lat : 30, lon : 12 }
                if ("nested_filter".equals(currentName) || "nestedFilter".equals(currentName)) {
                    ParsedFilter parsedFilter = context.queryParserService().parseInnerFilter(parser);
                    nestedFilter = parsedFilter == null ? null : parsedFilter.filter();
                } else {
                    fieldName = currentName;
                    GeoUtils.parseGeoPoint(parser, point);
                }
            } else if (token.isValue()) {
                if ("reverse".equals(currentName)) {
                    reverse = parser.booleanValue();
                } else if ("order".equals(currentName)) {
                    reverse = "desc".equals(parser.text());
                } else if (currentName.equals("unit")) {
                    unit = DistanceUnit.fromString(parser.text());
                } else if (currentName.equals("distance_type") || currentName.equals("distanceType")) {
                    geoDistance = GeoDistance.fromString(parser.text());
                } else if ("normalize".equals(currentName)) {
                    normalizeLat = parser.booleanValue();
                    normalizeLon = parser.booleanValue();
                } else if ("sort_mode".equals(currentName) || "sortMode".equals(currentName) || "mode".equals(currentName)) {
                    sortMode = MultiValueMode.fromString(parser.text());
                } else if ("nested_path".equals(currentName) || "nestedPath".equals(currentName)) {
                    nestedPath = parser.text();
                } else {
                    point.resetFromString(parser.text());
                    fieldName = currentName;
                }
            }
        }

        if (normalizeLat || normalizeLon) {
            GeoUtils.normalizePoint(point, normalizeLat, normalizeLon);
        }

        if (sortMode == null) {
            sortMode = reverse ? MultiValueMode.MAX : MultiValueMode.MIN;
        }

        if (sortMode == MultiValueMode.SUM) {
            throw new ElasticsearchIllegalArgumentException("sort_mode [sum] isn't supported for sorting by geo distance");
        }

        FieldMapper<?> mapper = context.smartNameFieldMapper(fieldName);
        if (mapper == null) {
            throw new ElasticsearchIllegalArgumentException("failed to find mapper for [" + fieldName + "] for geo distance based sort");
        }
        final MultiValueMode finalSortMode = sortMode; // final reference for use in the anonymous class
        final IndexGeoPointFieldData geoIndexFieldData = context.fieldData().getForField(mapper);
        final FixedSourceDistance distance = geoDistance.fixedSourceDistance(point.lat(), point.lon(), unit);
        ObjectMapper objectMapper;
        if (nestedPath != null) {
            ObjectMappers objectMappers = context.mapperService().objectMapper(nestedPath);
            if (objectMappers == null) {
                throw new ElasticsearchIllegalArgumentException("failed to find nested object mapping for explicit nested path [" + nestedPath + "]");
            }
            objectMapper = objectMappers.mapper();
            if (!objectMapper.nested().isNested()) {
                throw new ElasticsearchIllegalArgumentException("mapping for explicit nested path is not mapped as nested: [" + nestedPath + "]");
            }
        } else {
            objectMapper = context.mapperService().resolveClosestNestedObjectMapper(fieldName);
        }
        final Nested nested;
        if (objectMapper != null && objectMapper.nested().isNested()) {
            Filter rootDocumentsFilter = context.filterCache().cache(NonNestedDocsFilter.INSTANCE);
            Filter innerDocumentsFilter;
            if (nestedFilter != null) {
                innerDocumentsFilter = context.filterCache().cache(nestedFilter);
            } else {
                innerDocumentsFilter = context.filterCache().cache(objectMapper.nestedTypeFilter());
            }
            nested = new Nested(rootDocumentsFilter, innerDocumentsFilter);
        } else {
            nested = null;
        }

        IndexFieldData.XFieldComparatorSource geoDistanceComparatorSource = new IndexFieldData.XFieldComparatorSource() {

            @Override
            public SortField.Type reducedType() {
                return SortField.Type.DOUBLE;
            }

            @Override
            public FieldComparator<?> newComparator(String fieldname, int numHits, int sortPos, boolean reversed) throws IOException {
                return new FieldComparator.DoubleComparator(numHits, null, null, null) {
                    @Override
                    protected Doubles getDoubleValues(AtomicReaderContext context, String field) throws IOException {
                        final MultiGeoPointValues geoPointValues = geoIndexFieldData.load(context).getGeoPointValues();
                        final SortedNumericDoubleValues distanceValues = GeoDistance.distanceValues(distance, geoPointValues);
                        final NumericDoubleValues selectedValues;
                        if (nested == null) {
                            selectedValues = finalSortMode.select(distanceValues, Double.MAX_VALUE);
                        } else {
                            final FixedBitSet rootDocs = nested.rootDocs(context);
                            final FixedBitSet innerDocs = nested.innerDocs(context);
                            selectedValues = finalSortMode.select(distanceValues, Double.MAX_VALUE, rootDocs, innerDocs, context.reader().maxDoc());
                        }
                        return new Doubles() {
                            @Override
                            public double get(int docID) {
                                return selectedValues.get(docID);
                            }
                        };
                    }
                };
            }

        };

        return new SortField(fieldName, geoDistanceComparatorSource, reverse);
    }
}
