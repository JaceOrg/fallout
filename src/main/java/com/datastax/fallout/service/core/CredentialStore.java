/*
 * Copyright 2022 DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.fallout.service.core;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.UpdateSecretRequest;

import com.datastax.fallout.util.JsonUtils;

public abstract class CredentialStore
{
    private final Map<String, User.CredentialSet> userCredentialsCache = new HashMap<>();

    public void readUsersCredentialSet(User user)
    {
        if (userCredentialsCache.containsKey(user.getEmail()))
        {
            user.setCredentialsSet(userCredentialsCache.get(user.getEmail()));
        }
        var credSet = getCredentialsFromStore(user);
        userCredentialsCache.put(user.getEmail(), credSet);
        user.setCredentialsSet(credSet);
    }

    public void updateUserCredentialsSet(User user)
    {
        updateCredentialsInStore(user);
        userCredentialsCache.put(user.getEmail(), user.getCredentialsSet());
    }

    public void createNewUserCredentialSet(User user)
    {
        createNewCredentialsInStore(user);
        userCredentialsCache.put(user.getEmail(), user.getCredentialsSet());
    }

    abstract void createNewCredentialsInStore(User user);

    abstract User.CredentialSet getCredentialsFromStore(User user);

    abstract void updateCredentialsInStore(User user);

    public static class NoopCredentialStore extends CredentialStore
    {
        @Override
        public void createNewCredentialsInStore(User user)
        {
        }

        @Override
        public User.CredentialSet getCredentialsFromStore(User user)
        {
            return new User.CredentialSet();
        }

        @Override
        public void updateCredentialsInStore(User user)
        {
        }
    }

    public static class AwsSecretsManagerCredentialStore extends CredentialStore
    {
        private static final Region SECRETS_REGION = Region.US_WEST_2;
        private final String kmsKeyId;
        private final SecretsManagerClient client;

        public AwsSecretsManagerCredentialStore(String kmsKeyId)
        {
            this.kmsKeyId = kmsKeyId;
            this.client = SecretsManagerClient.builder().region(SECRETS_REGION).build();
        }

        @Override
        public void createNewCredentialsInStore(User user)
        {
            var req = CreateSecretRequest.builder()
                .name(String.format("%s-user-credentials", user.getEmail()))
                .description(String.format("Fallout user credentials for %s", user.getEmail()))
                .secretString(JsonUtils.toJson(user.getCredentialsSet()))
                .kmsKeyId(kmsKeyId)
                .build();
            var res = client.createSecret(req);
            user.setCredentialStoreKey(res.arn());
        }

        @Override
        public User.CredentialSet getCredentialsFromStore(User user)
        {
            var req = GetSecretValueRequest.builder()
                .secretId(user.getCredentialStoreKey())
                .build();
            var res = client.getSecretValue(req);
            return JsonUtils.fromJson(res.secretString(), User.CredentialSet.class);
        }

        @Override
        public void updateCredentialsInStore(User user)
        {
            if (null == user.getCredentialStoreKey())
            {
                createNewUserCredentialSet(user);
                return;
            }
            var req = UpdateSecretRequest.builder()
                .secretId(user.getCredentialStoreKey())
                .secretString(JsonUtils.toJson(user.getCredentialsSet()))
                .build();
            client.updateSecret(req);
        }
    }
}
