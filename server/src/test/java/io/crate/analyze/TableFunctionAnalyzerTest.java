/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import static io.crate.testing.SymbolMatchers.isReference;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SQLExecutor;

public class TableFunctionAnalyzerTest extends CrateDummyClusterServiceUnitTest {

    private SQLExecutor e;

    @Before
    public void prepare() throws IOException {
        e = SQLExecutor.builder(clusterService).build();
    }

    @Test
    public void TestTableFunctionNameIsUsedWhenTableFunctionReturnsBaseDataType() {
        var analyzedRelation = e.analyze("select * from regexp_matches('a', 'a')");
        assertThat(analyzedRelation.outputs().get(0), isReference("regexp_matches"));

        analyzedRelation = e.analyze(
            """
                select * from generate_series('2019-01-01 00:00'::timestamp, '2019-01-04 00:00'::timestamp, '30 hours'::interval)
                """
        );
        assertThat(analyzedRelation.outputs().get(0), isReference("generate_series"));

        // There are other table functions should be tested here
        // For example, https://crate.io/docs/crate/reference/en/4.8/general/builtins/table-functions.html
        // but they are implicitly handled in other tests, hence omitted.
    }
}
