/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */

package org.cloudfoundry.identity.uaa.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.oauth.client.ClientConstants;
import org.cloudfoundry.identity.uaa.oauth.jwt.Jwt;
import org.cloudfoundry.identity.uaa.oauth.jwt.JwtHelper;
import org.cloudfoundry.identity.uaa.oauth.token.RevocableToken;
import org.cloudfoundry.identity.uaa.oauth.token.RevocableTokenProvisioning;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceNotFoundException;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.MultitenantJdbcClientDetailsService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.common.exceptions.InsufficientScopeException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.common.exceptions.OAuth2Exception;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.security.oauth2.provider.error.DefaultWebResponseExceptionTranslator;
import org.springframework.security.oauth2.provider.error.WebResponseExceptionTranslator;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.OK;

@Controller
public class TokenRevocationEndpoint {

    protected final Log logger = LogFactory.getLog(getClass());
    private WebResponseExceptionTranslator exceptionTranslator = new DefaultWebResponseExceptionTranslator();
    private final ScimUserProvisioning userProvisioning;
    private final MultitenantJdbcClientDetailsService clientDetailsService;
    private final RandomValueStringGenerator generator = new RandomValueStringGenerator(8);
    private final RevocableTokenProvisioning tokenProvisioning;

    public TokenRevocationEndpoint(MultitenantJdbcClientDetailsService clientDetailsService, ScimUserProvisioning userProvisioning, RevocableTokenProvisioning tokenProvisioning) {
        this.clientDetailsService = clientDetailsService;
        this.userProvisioning = userProvisioning;
        this.tokenProvisioning = tokenProvisioning;
    }

    @RequestMapping("/oauth/token/revoke/user/{userId}")
    public ResponseEntity<Void> revokeTokensForUser(@PathVariable String userId) {
        logger.debug("Revoking tokens for user: " + userId);
        ScimUser user = userProvisioning.retrieve(userId);
        user.setSalt(generator.generate());
        userProvisioning.update(userId, user);
        logger.debug("Tokens revoked for user: " + userId);
        return new ResponseEntity<>(OK);
    }

    @RequestMapping("/oauth/token/revoke/client/{clientId}")
    public ResponseEntity<Void> revokeTokensForClient(@PathVariable String clientId) {
        logger.debug("Revoking tokens for client: " + clientId);
        BaseClientDetails client = (BaseClientDetails)clientDetailsService.loadClientByClientId(clientId);
        client.addAdditionalInformation(ClientConstants.TOKEN_SALT,generator.generate());
        clientDetailsService.updateClientDetails(client);
        logger.debug("Tokens revoked for client: " + clientId);
        return new ResponseEntity<>(OK);
    }

    @RequestMapping(value = "/oauth/token/revoke/{tokenId}", method = RequestMethod.DELETE)
    public ResponseEntity<Void> revokeTokenById(@PathVariable String tokenId,
                                                HttpServletRequest request) {
        logger.debug("Revoking token");
        String authorization = request.getHeader("Authorization");
        String requestToken = authorization.split(" ")[1];
        Jwt jwt = JwtHelper.decode(requestToken);
        Map<String, Object> claims = JsonUtils.readValue(jwt.getClaims(), new TypeReference<Map<String, Object>>() {});

        RevocableToken token = tokenProvisioning.retrieve(tokenId);

        List<String> scopes = (List<String>) claims.get("scope");

        if (!scopes.contains("tokens.revoke") && !scopes.contains("uaa.admin")) {
            String userId = (String) claims.get("user_id");
            String clientId = (String) claims.get("client_id");
            if (StringUtils.hasText(userId)) {
                if (token.getUserId().equals(userId)) {
                    tokenProvisioning.delete(tokenId, -1);
                    logger.debug("Revoked user token with ID: " + tokenId);
                    return new ResponseEntity<>(OK);
                }
            } else if (StringUtils.hasText(clientId)) {
                if (token.getClientId().equals(clientId)) {
                    tokenProvisioning.delete(tokenId, -1);
                    logger.debug("Revoked client token with ID: " + tokenId);
                    return new ResponseEntity<>(OK);
                }
            }
        } else {
            tokenProvisioning.delete(tokenId, -1);
            logger.debug("Revoked token with ID: " + tokenId);
            return new ResponseEntity<>(OK);
        }

        throw new InsufficientScopeException("Cannot revoke others' tokens unless you have either `tokens.revoke` or `uaa.admin`");
    }

    @ExceptionHandler({ScimResourceNotFoundException.class, NoSuchClientException.class, EmptyResultDataAccessException.class})
    public ResponseEntity<OAuth2Exception> handleException(Exception e) throws Exception {
        logger.info("Handling error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
        InvalidTokenException e404 = new InvalidTokenException("Resource not found") {
            @Override
            public int getHttpErrorCode() {
                return 404;
            }
        };
        return exceptionTranslator.translate(e404);
    }

    @ExceptionHandler(InsufficientScopeException.class)
    public ResponseEntity<InsufficientScopeException> handleInvalidClientDetails(InsufficientScopeException e) {
        return new ResponseEntity<>(e, HttpStatus.FORBIDDEN);
    }
}
