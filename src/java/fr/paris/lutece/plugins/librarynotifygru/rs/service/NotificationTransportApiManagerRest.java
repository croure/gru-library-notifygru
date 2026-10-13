/*
 * Copyright (c) 2002-2026, City of Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.librarynotifygru.rs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.paris.lutece.plugins.grubusiness.business.notification.Notification;
import fr.paris.lutece.plugins.grubusiness.business.notification.NotifyGruResponse;
import fr.paris.lutece.plugins.librarynotifygru.exception.NotifyGruException;
import fr.paris.lutece.util.httpaccess.InvalidResponseStatus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.commons.lang3.StringUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * NotificationTransport which authenticates with an API Manager OAuth2 token (client credentials). The token is kept in memory, per instance, until it
 * expires: it is a secret, it is never shared between instances.
 */
@ApplicationScoped
@Named( NotificationTransportApiManagerRest.BEAN_NAME )
public class NotificationTransportApiManagerRest extends AbstractNotificationTransportRest
{
    public static final String BEAN_NAME = "library-notifygru.apiManagerTransport";
    /** The Constant PARAMS_ACCES_TOKEN. */
    private static final String PARAMS_ACCES_TOKEN = "access_token";
    private static final String PARAMS_EXPIRES_IN = "expires_in";
    private static final String TYPE_AUTHENTIFICATION_BEARER = "Bearer";
    private static final String TYPE_AUTHENTIFICATION_BASIC = "Basic";

    /** The Constant PARAMS_GRANT_TYPE. */
    private static final String PARAMS_GRANT_TYPE = "grant_type";

    /** The Constant PARAMS_GRANT_TYPE_VALUE. */
    private static final String PARAMS_GRANT_TYPE_VALUE = "client_credentials";

    /** Seconds removed from the token lifetime, so that a token is never sent just before it expires */
    static final int DEFAULT_TOKEN_EXPIRY_MARGIN = 60;
    private static final int HTTP_STATUS_UNAUTHORIZED = 401;
    private static final long MILLISECONDS_PER_SECOND = 1000L;

    private static Logger _logger = LogManager.getLogger( NotificationTransportApiManagerRest.class );

    /** URL for REST service apiManager */
    private String _strApiManagerEndPoint;
    private String _strApiManagerCredentials;
    private int _nTokenExpiryMargin = DEFAULT_TOKEN_EXPIRY_MARGIN;

    /** The cached token, null when there is none */
    private volatile CachedToken _cachedToken;
    private final Object _lockToken = new Object( );
    private LongSupplier _clock = System::currentTimeMillis;

    /**
     * Simple Constructor
     */
    public NotificationTransportApiManagerRest( )
    {
        super( );
        this.setHttpTransport( new HttpAccessTransport( ) );
    }

    /**
     * Constructor
     * 
     * @param strNotificationEndPoint
     *            the notification end point
     * @param strApiManagerEndPoint
     *            the token end point
     * @param strApiManagerCredentials
     *            the API Manager credentials
     */
    public NotificationTransportApiManagerRest( String strNotificationEndPoint, String strApiManagerEndPoint, String strApiManagerCredentials )
    {
        this( strNotificationEndPoint, strApiManagerEndPoint, strApiManagerCredentials, DEFAULT_TOKEN_EXPIRY_MARGIN );
    }

    /**
     * Constructor
     * 
     * @param strNotificationEndPoint
     *            the notification end point
     * @param strApiManagerEndPoint
     *            the token end point
     * @param strApiManagerCredentials
     *            the API Manager credentials
     * @param nTokenExpiryMargin
     *            the seconds removed from the token lifetime
     */
    @Inject
    public NotificationTransportApiManagerRest(
            @ConfigProperty( name = "library-notifygru.NotificationStoreNotifierService.notificationEndPoint" ) String strNotificationEndPoint,
            @ConfigProperty( name = "library-notifygru.NotificationStoreNotifierService.apiManagerEndPoint" ) String strApiManagerEndPoint,
            @ConfigProperty( name = "library-notifygru.NotificationStoreNotifierService.apiManagerCredentials" ) String strApiManagerCredentials,
            @ConfigProperty( name = "library-notifygru.NotificationStoreNotifierService.apiManagerTokenExpiryMargin", defaultValue = "60" ) int nTokenExpiryMargin )
    {
        this( );
        this.setNotificationEndPoint( strNotificationEndPoint );
        this._strApiManagerEndPoint = strApiManagerEndPoint;
        this._strApiManagerCredentials = strApiManagerCredentials;
        this._nTokenExpiryMargin = nTokenExpiryMargin;
    }

    /**
     * setter of apiManagerEndPoint
     * 
     * @param strApiManagerEndPoint
     *            value to use
     */
    public void setApiManagerEndPoint( String strApiManagerEndPoint )
    {
        this._strApiManagerEndPoint = strApiManagerEndPoint;
        invalidateToken( );
    }

    /**
     * Sets the API Manager credentials
     * 
     * @param strApiManagerCredentials
     *            the API Manager credentials
     */
    public void setApiManagerCredentials( String strApiManagerCredentials )
    {
        this._strApiManagerCredentials = strApiManagerCredentials;
        invalidateToken( );
    }

    /**
     * Sets the seconds removed from the token lifetime before it is renewed
     * 
     * @param nTokenExpiryMargin
     *            the margin in seconds
     */
    public void setTokenExpiryMargin( int nTokenExpiryMargin )
    {
        this._nTokenExpiryMargin = nTokenExpiryMargin;
    }

    /**
     * Sets the clock, for the tests
     * 
     * @param clock
     *            the current time in milliseconds
     */
    void setClock( LongSupplier clock )
    {
        this._clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NotifyGruResponse send( Notification notification )
    {
        String strToken = getToken( );

        try
        {
            return send( notification, buildAuthenticationHeaders( strToken ) );
        }
        catch( NotifyGruException e )
        {
            if ( !isUnauthorized( e ) )
            {
                throw e;
            }

            _logger.info( "LibraryNotifyGru - NotificationTransportApiManagerRest.send token rejected, retry once with a new token" );
            invalidateToken( strToken );

            return send( notification, buildAuthenticationHeaders( getToken( ) ) );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addAuthentication( Map<String, String> mapHeadersRequest )
    {
        mapHeadersRequest.putAll( buildAuthenticationHeaders( getToken( ) ) );
    }

    /**
     * Builds the request headers with the bearer token
     * 
     * @param strToken
     *            the token
     * @return the headers
     */
    private Map<String, String> buildAuthenticationHeaders( String strToken )
    {
        Map<String, String> mapHeadersRequest = new HashMap<>( );

        if ( StringUtils.isNotBlank( strToken ) )
        {
            mapHeadersRequest.put( HttpAccessTransport.HTTP_HEADER_AUTHORIZATION, TYPE_AUTHENTIFICATION_BEARER + " " + strToken );
        }

        return mapHeadersRequest;
    }

    /**
     * Gets the security token, from the cache while it is valid, else from API Manager. Only one caller renews it, the others wait for it.
     * 
     * @return the token
     */
    String getToken( )
    {
        CachedToken cachedToken = _cachedToken;

        if ( cachedToken != null && cachedToken.isValid( _clock.getAsLong( ) ) )
        {
            return cachedToken.getToken( );
        }

        synchronized( _lockToken )
        {
            cachedToken = _cachedToken;

            if ( cachedToken != null && cachedToken.isValid( _clock.getAsLong( ) ) )
            {
                return cachedToken.getToken( );
            }

            return requestToken( );
        }
    }

    /**
     * Forgets the cached token
     */
    void invalidateToken( )
    {
        _cachedToken = null;
    }

    /**
     * Forgets the cached token if it is the rejected one, so that a token already renewed by another caller is kept
     * 
     * @param strRejectedToken
     *            the rejected token
     */
    private void invalidateToken( String strRejectedToken )
    {
        synchronized( _lockToken )
        {
            CachedToken cachedToken = _cachedToken;

            if ( cachedToken != null && cachedToken.getToken( ).equals( strRejectedToken ) )
            {
                _cachedToken = null;
            }
        }
    }

    /**
     * Requests a new security token from API Manager and caches it until its expiry, minus the margin. A token without expires_in is not cached.
     * 
     * @return the token
     */
    private String requestToken( )
    {
        String strToken = StringUtils.EMPTY;

        _logger.debug( "LibraryNotifyGru - NotificationTransportApiManagerRest.getToken with URL_TOKEN property [ {} ]", _strApiManagerEndPoint );

        Map<String, String> mapHeadersRequest = new HashMap<>( );
        Map<String, String> mapParams = new HashMap<>( );

        mapParams.put( PARAMS_GRANT_TYPE, PARAMS_GRANT_TYPE_VALUE );

        mapHeadersRequest.put( HttpAccessTransport.HTTP_HEADER_ACCEPT, HttpAccessTransport.HTTP_HEADER_MEDIATYPE_APPLICATION_JSON );
        mapHeadersRequest.put( HttpAccessTransport.HTTP_HEADER_CONTENT_TYPE, HttpAccessTransport.HTTP_HEADER_MEDIATYPE_APPLICATION_FORM_URLENCODED );
        mapHeadersRequest.put( HttpAccessTransport.HTTP_HEADER_AUTHORIZATION, TYPE_AUTHENTIFICATION_BASIC + " " + _strApiManagerCredentials );

        long lRequestTime = _clock.getAsLong( );
        String strJson = getHttpTransport( ).doPost( _strApiManagerEndPoint, mapParams, mapHeadersRequest );

        ObjectMapper mapper = new ObjectMapper( );
        mapper.configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false );

        try
        {
            JsonNode jsonNode = mapper.readTree( strJson );

            JsonNode jsonTokenNode = jsonNode.get( PARAMS_ACCES_TOKEN );

            if ( jsonTokenNode == null || StringUtils.isBlank( jsonTokenNode.textValue( ) ) )
            {
                _logger.error( "LibraryNotifyGru - NotificationTransportApiManagerRest.getToken response without access_token" );
                return strToken;
            }

            strToken = jsonTokenNode.textValue( );

            JsonNode jsonExpiresInNode = jsonNode.get( PARAMS_EXPIRES_IN );
            long lLifetime = ( jsonExpiresInNode != null ) ? jsonExpiresInNode.asLong( ) - _nTokenExpiryMargin : 0;

            if ( lLifetime > 0 )
            {
                _cachedToken = new CachedToken( strToken, lRequestTime + lLifetime * MILLISECONDS_PER_SECOND );
            }
            else
            {
                _logger.debug( "LibraryNotifyGru - NotificationTransportApiManagerRest.getToken token not cached : no expires_in or shorter than the margin" );
            }
        }
        catch( JsonProcessingException e )
        {
            _logger.error( "LibraryNotifyGru - NotificationTransportApiManagerRest.getToken invalid response [ {} ]", strJson );
        }

        return strToken;
    }

    /**
     * Tells whether the notification was refused because of the token
     * 
     * @param exception
     *            the exception
     * @return true if the response status is 401
     */
    private static boolean isUnauthorized( Throwable exception )
    {
        for ( Throwable cause = exception; cause != null; cause = cause.getCause( ) )
        {
            if ( cause instanceof InvalidResponseStatus invalidResponseStatus )
            {
                return invalidResponseStatus.getResponseStatus( ) == HTTP_STATUS_UNAUTHORIZED;
            }
        }

        return false;
    }

    /**
     * A token and its expiry date, immutable
     */
    private static final class CachedToken
    {
        private final String _strToken;
        private final long _lExpiryTime;

        CachedToken( String strToken, long lExpiryTime )
        {
            _strToken = strToken;
            _lExpiryTime = lExpiryTime;
        }

        String getToken( )
        {
            return _strToken;
        }

        boolean isValid( long lNow )
        {
            return lNow < _lExpiryTime;
        }
    }
}
