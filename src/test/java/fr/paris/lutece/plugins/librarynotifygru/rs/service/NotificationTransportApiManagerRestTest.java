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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.paris.lutece.plugins.grubusiness.business.notification.Notification;
import fr.paris.lutece.plugins.grubusiness.business.notification.NotifyGruResponse;
import fr.paris.lutece.plugins.librarynotifygru.exception.NotifyGruException;
import fr.paris.lutece.util.httpaccess.InvalidResponseStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test of the token cache of NotificationTransportApiManagerRest
 */
public class NotificationTransportApiManagerRestTest
{
    private static final String AUTHORIZATION = HttpAccessTransport.HTTP_HEADER_AUTHORIZATION;

    private FakeHttpTransport _httpTransport;
    private NotificationTransportApiManagerRest _transport;
    private AtomicLong _lNow;

    @BeforeEach
    void setUp( )
    {
        _httpTransport = new FakeHttpTransport( );
        _lNow = new AtomicLong( 1_000_000L );
        _transport = new NotificationTransportApiManagerRest( );
        _transport.setHttpTransport( _httpTransport );
        _transport.setNotificationEndPoint( "http://notification" );
        _transport.setApiManagerEndPoint( "http://token" );
        _transport.setApiManagerCredentials( "credentials" );
        _transport.setTokenExpiryMargin( 60 );
        _transport.setClock( _lNow::get );
    }

    /**
     * The token is requested once and reused while it is valid
     */
    @Test
    void testTokenReusedWhileValid( )
    {
        _transport.send( new Notification( ) );
        _transport.send( new Notification( ) );
        _lNow.addAndGet( 3_539_000L );
        _transport.send( new Notification( ) );

        assertEquals( 1, _httpTransport._nTokenCalls.get( ) );
        assertEquals( List.of( "Bearer token-1", "Bearer token-1", "Bearer token-1" ), _httpTransport._listAuthorizations );
    }

    /**
     * The token is renewed once its lifetime minus the margin is over
     */
    @Test
    void testTokenRenewedAfterExpiry( )
    {
        _transport.send( new Notification( ) );
        _lNow.addAndGet( 3_540_000L );
        _transport.send( new Notification( ) );

        assertEquals( 2, _httpTransport._nTokenCalls.get( ) );
        assertEquals( List.of( "Bearer token-1", "Bearer token-2" ), _httpTransport._listAuthorizations );
    }

    /**
     * A token without expires_in is not cached
     */
    @Test
    void testTokenWithoutExpiryNotCached( )
    {
        _httpTransport._nExpiresIn = null;

        _transport.send( new Notification( ) );
        _transport.send( new Notification( ) );

        assertEquals( 2, _httpTransport._nTokenCalls.get( ) );
    }

    /**
     * A 401 invalidates the token and the notification is sent again once with a new token
     */
    @Test
    void testUnauthorizedRetriedOnceWithNewToken( )
    {
        _transport.send( new Notification( ) );
        _httpTransport._nUnauthorizedResponses = 1;

        NotifyGruResponse response = _transport.send( new Notification( ) );

        assertEquals( NotifyGruResponse.STATUS_RECEIVED, response.getStatus( ) );
        assertEquals( 2, _httpTransport._nTokenCalls.get( ) );
        assertEquals( List.of( "Bearer token-1", "Bearer token-1", "Bearer token-2" ), _httpTransport._listAuthorizations );

        _transport.send( new Notification( ) );
        assertEquals( 2, _httpTransport._nTokenCalls.get( ) );
    }

    /**
     * A second 401 is not retried again
     */
    @Test
    void testUnauthorizedRetriedOnlyOnce( )
    {
        _httpTransport._nUnauthorizedResponses = 2;

        assertThrows( NotifyGruException.class, ( ) -> _transport.send( new Notification( ) ) );
        assertEquals( 2, _httpTransport._listAuthorizations.size( ) );
    }

    /**
     * An error other than 401 is not retried and keeps the token
     */
    @Test
    void testOtherErrorNotRetried( )
    {
        _httpTransport._nServerErrorResponses = 1;

        assertThrows( NotifyGruException.class, ( ) -> _transport.send( new Notification( ) ) );
        _transport.send( new Notification( ) );

        assertEquals( 1, _httpTransport._nTokenCalls.get( ) );
        assertEquals( 2, _httpTransport._listAuthorizations.size( ) );
    }

    /**
     * Concurrent callers without a valid token trigger a single token request
     * 
     * @throws Exception
     *             if a thread fails
     */
    @Test
    void testConcurrentCallersRequestOneToken( ) throws Exception
    {
        int nThreads = 16;
        _httpTransport._lTokenDelay = 200L;
        ExecutorService executor = Executors.newFixedThreadPool( nThreads );
        CountDownLatch start = new CountDownLatch( 1 );
        List<Future<String>> listResults = new ArrayList<>( );

        try
        {
            for ( int i = 0; i < nThreads; i++ )
            {
                Callable<String> task = ( ) -> {
                    start.await( );
                    return _transport.getToken( );
                };
                listResults.add( executor.submit( task ) );
            }

            start.countDown( );

            for ( Future<String> result : listResults )
            {
                assertEquals( "token-1", result.get( 10, TimeUnit.SECONDS ) );
            }
        }
        finally
        {
            executor.shutdownNow( );
        }

        assertEquals( 1, _httpTransport._nTokenCalls.get( ) );
    }

    /**
     * Fake HTTP transport: a token end point and a notification end point
     */
    private static final class FakeHttpTransport implements IHttpTransportProvider
    {
        private final AtomicInteger _nTokenCalls = new AtomicInteger( );
        private final List<String> _listAuthorizations = Collections.synchronizedList( new ArrayList<>( ) );
        private volatile Integer _nExpiresIn = 3600;
        private volatile long _lTokenDelay;
        private int _nUnauthorizedResponses;
        private int _nServerErrorResponses;

        @Override
        public String doPost( String strUrl, Map<String, String> mapParams, Map<String, String> mapHeadersRequest )
        {
            int nCall = _nTokenCalls.incrementAndGet( );

            if ( _lTokenDelay > 0 )
            {
                try
                {
                    Thread.sleep( _lTokenDelay );
                }
                catch( InterruptedException e )
                {
                    Thread.currentThread( ).interrupt( );
                }
            }

            String strExpiresIn = ( _nExpiresIn != null ) ? ",\"expires_in\":" + _nExpiresIn : "";

            return "{\"access_token\":\"token-" + nCall + "\",\"token_type\":\"Bearer\"" + strExpiresIn + "}";
        }

        @Override
        public <T> T doPostJSON( String strUrl, Map<String, String> mapParams, Map<String, String> mapHeadersRequest, Object json, Class<T> responseJsonClass,
                ObjectMapper mapper )
        {
            _listAuthorizations.add( mapHeadersRequest.get( AUTHORIZATION ) );

            if ( _nUnauthorizedResponses > 0 )
            {
                _nUnauthorizedResponses--;
                throw new NotifyGruException( "unauthorized", new InvalidResponseStatus( "unauthorized", 401, "", null ) );
            }

            if ( _nServerErrorResponses > 0 )
            {
                _nServerErrorResponses--;
                throw new NotifyGruException( "server error", new InvalidResponseStatus( "server error", 500, "", null ) );
            }

            NotifyGruResponse response = new NotifyGruResponse( );
            response.setStatus( NotifyGruResponse.STATUS_RECEIVED );

            return responseJsonClass.cast( response );
        }
    }
}
