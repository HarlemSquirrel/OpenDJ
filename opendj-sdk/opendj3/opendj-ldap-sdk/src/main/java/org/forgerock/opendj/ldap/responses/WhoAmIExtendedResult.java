/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 *      Portions copyright 2012 ForgeRock AS.
 */

package org.forgerock.opendj.ldap.responses;



import java.util.List;

import org.forgerock.i18n.*;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DecodeException;
import org.forgerock.opendj.ldap.DecodeOptions;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.controls.Control;
import org.forgerock.opendj.ldap.controls.ControlDecoder;



/**
 * The who am I extended result as defined in RFC 4532. The result includes the
 * primary authorization identity, in its primary form, that the server has
 * associated with the user or application entity, but only if the who am I
 * request succeeded.
 * <p>
 * The authorization identity is specified using an authorization ID, or {@code
 * authzId}, as defined in RFC 4513 section 5.2.1.8.
 *
 * @see org.forgerock.opendj.ldap.requests.WhoAmIExtendedRequest
 * @see org.forgerock.opendj.ldap.controls.AuthorizationIdentityRequestControl
 * @see <a href="http://tools.ietf.org/html/rfc4532">RFC 4532 - Lightweight
 *      Directory Access Protocol (LDAP) "Who am I?" Operation </a>
 * @see <a href="http://tools.ietf.org/html/rfc4513#section-5.2.1.8">RFC 4513 -
 *      SASL Authorization Identities (authzId) </a>
 */
public interface WhoAmIExtendedResult extends ExtendedResult
{

  /**
   * {@inheritDoc}
   */
  WhoAmIExtendedResult addControl(Control control);



  /**
   * {@inheritDoc}
   */
  WhoAmIExtendedResult addReferralURI(String uri);



  /**
   * Returns the authorization ID of the user. The authorization ID usually has
   * the form "dn:" immediately followed by the distinguished name of the user,
   * or "u:" followed by a user ID string, but other forms are permitted.
   *
   * @return The authorization ID of the user, or {@code null} if this result
   *         does not contain an authorization ID.
   */
  String getAuthorizationID();



  /**
   * {@inheritDoc}
   */
  Throwable getCause();



  /**
   * {@inheritDoc}
   */
  <C extends Control> C getControl(ControlDecoder<C> decoder,
      DecodeOptions options) throws DecodeException;



  /**
   * {@inheritDoc}
   */
  List<Control> getControls();



  /**
   * {@inheritDoc}
   */
  String getDiagnosticMessage();



  /**
   * {@inheritDoc}
   */
  String getMatchedDN();



  /**
   * {@inheritDoc}
   */
  String getOID();



  /**
   * {@inheritDoc}
   */
  List<String> getReferralURIs();



  /**
   * {@inheritDoc}
   */
  ResultCode getResultCode();



  /**
   * {@inheritDoc}
   */
  ByteString getValue();



  /**
   * {@inheritDoc}
   */
  boolean hasValue();



  /**
   * {@inheritDoc}
   */
  boolean isReferral();



  /**
   * {@inheritDoc}
   */
  boolean isSuccess();



  /**
   * Sets the authorization ID of the user. The authorization ID usually has the
   * form "dn:" immediately followed by the distinguished name of the user, or
   * "u:" followed by a user ID string, but other forms are permitted.
   *
   * @param authorizationID
   *          The authorization ID of the user, which may be {@code null} if
   *          this result does not contain an authorization ID.
   * @return This who am I result.
   * @throws LocalizedIllegalArgumentException
   *           If {@code authorizationID} was non-empty and did not contain a
   *           valid authorization ID type.
   * @throws UnsupportedOperationException
   *           If this who am I extended result does not permit the
   *           authorization ID to be set.
   */
  WhoAmIExtendedResult setAuthorizationID(String authorizationID);



  /**
   * {@inheritDoc}
   */
  WhoAmIExtendedResult setCause(Throwable cause);



  /**
   * {@inheritDoc}
   */
  WhoAmIExtendedResult setDiagnosticMessage(String message);



  /**
   * {@inheritDoc}
   */
  WhoAmIExtendedResult setMatchedDN(String dn);



  /**
   * {@inheritDoc}
   */
  WhoAmIExtendedResult setResultCode(ResultCode resultCode);

}
