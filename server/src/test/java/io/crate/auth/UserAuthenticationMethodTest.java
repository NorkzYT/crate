/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.auth;

import static org.hamcrest.core.Is.is;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Collections;
import java.util.List;

import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import io.crate.user.SecureHash;
import io.crate.user.User;
import io.crate.user.UserLookup;

public class UserAuthenticationMethodTest extends ESTestCase {

    class CrateOrNullUserLookup implements UserLookup {

        @Override
        public Iterable<User> users() {
            SecureHash pwHash;
            try {
                pwHash = SecureHash.of(new SecureString("pw".toCharArray()));
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new RuntimeException(e);
            }
            return List.of(User.of("crate", Collections.emptySet(), pwHash));
        }
    }

    @Test
    public void testTrustAuthentication() throws Exception {
        TrustAuthenticationMethod trustAuth = new TrustAuthenticationMethod(new CrateOrNullUserLookup());
        assertThat(trustAuth.name(), is("trust"));

        assertThat(trustAuth.authenticate("crate", null, null).name(), is("crate"));

        expectedException.expectMessage("trust authentication failed for user \"cr8\"");
        trustAuth.authenticate("cr8", null, null);
    }

    @Test
    public void testAlwaysOKAuthentication() throws Exception {
        AlwaysOKAuthentication alwaysOkAuth = new AlwaysOKAuthentication(new CrateOrNullUserLookup());
        AuthenticationMethod alwaysOkAuthMethod = alwaysOkAuth.resolveAuthenticationType("crate", null);

        assertThat(alwaysOkAuthMethod.name(), is("trust"));
        assertThat(alwaysOkAuthMethod.authenticate("crate", null, null).name(), is("crate"));

        expectedException.expectMessage("authentication failed for user \"cr8\"");
        alwaysOkAuthMethod.authenticate("cr8", null, null);
    }

    public void testPasswordAuthentication() throws Exception {
        PasswordAuthenticationMethod pwAuth = new PasswordAuthenticationMethod(new CrateOrNullUserLookup());
        assertThat(pwAuth.name(), is("password"));

        assertThat(pwAuth.authenticate("crate", new SecureString("pw".toCharArray()), null).name(), is("crate"));
    }

    @Test
    public void testPasswordAuthenticationWrongPassword() throws Exception {
        PasswordAuthenticationMethod pwAuth = new PasswordAuthenticationMethod(new CrateOrNullUserLookup());
        assertThat(pwAuth.name(), is("password"));

        expectedException.expectMessage("password authentication failed for user \"crate\"");
        pwAuth.authenticate("crate", new SecureString("wrong".toCharArray()), null);
    }

    @Test
    public void testPasswordAuthenticationForNonExistingUser() throws Exception {
        PasswordAuthenticationMethod pwAuth = new PasswordAuthenticationMethod(new CrateOrNullUserLookup());
        expectedException.expectMessage("password authentication failed for user \"cr8\"");
        pwAuth.authenticate("cr8", new SecureString("pw".toCharArray()), null);
    }
}
