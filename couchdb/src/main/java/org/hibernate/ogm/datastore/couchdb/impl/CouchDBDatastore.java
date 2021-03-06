/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.hibernate.ogm.datastore.couchdb.impl;

import java.util.List;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.hibernate.HibernateException;
import org.hibernate.ogm.datastore.spi.Tuple;
import org.hibernate.ogm.dialect.couchdb.designdocument.AssociationsDesignDocument;
import org.hibernate.ogm.dialect.couchdb.designdocument.CouchDBDesignDocument;
import org.hibernate.ogm.dialect.couchdb.designdocument.EntitiesDesignDocument;
import org.hibernate.ogm.dialect.couchdb.designdocument.EntityTupleRows;
import org.hibernate.ogm.dialect.couchdb.designdocument.TuplesDesignDocument;
import org.hibernate.ogm.dialect.couchdb.json.CouchDBAssociation;
import org.hibernate.ogm.dialect.couchdb.json.CouchDBCountResponse;
import org.hibernate.ogm.dialect.couchdb.json.CouchDBDocument;
import org.hibernate.ogm.dialect.couchdb.json.CouchDBEntity;
import org.hibernate.ogm.dialect.couchdb.json.CouchDBKeyValue;
import org.hibernate.ogm.dialect.couchdb.json.CouchDBResponse;
import org.hibernate.ogm.dialect.couchdb.json.DatabaseClient;
import org.hibernate.ogm.dialect.couchdb.json.ServerClient;
import org.hibernate.ogm.dialect.couchdb.util.DataBaseURL;
import org.hibernate.ogm.grid.EntityKeyMetadata;
import org.hibernate.ogm.grid.RowKey;
import org.hibernate.ogm.logging.couchdb.impl.Log;
import org.hibernate.ogm.logging.couchdb.impl.LoggerFactory;
import org.jboss.resteasy.client.exception.ResteasyClientException;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

/**
 * Provides access and interaction with a database instance of CouchDB.
 *
 * @author Andrea Boriero <dreborier@gmail.com/>
 */
public class CouchDBDatastore {

	private static final Log logger = LoggerFactory.getLogger();
	private final DatabaseClient databaseClient;
	private final ServerClient serverClient;

	private CouchDBDatastore(DataBaseURL databaseUrl, String userName, String password) {
		logger.connectingToCouchDB( databaseUrl.toString() );
		serverClient = createServerClient( databaseUrl );
		databaseClient = createDataBaseClient( databaseUrl );
	}

	/**
	 * Creates an instance of CouchDBDatastore, check if the CouchDB Design Document necessary to retrieve some data
	 * (e.g. number of associations and entities) are present and create them if not
	 *
	 * @param databaseURL the url of the database
	 * @param userName the username of the database user or null if authentication is not required
	 * @param password the password of the database user or null if authentication is not required
	 * @param createDatabase if true the database is created
	 * @return an instance of CouchDBDatastore
	 */
	public static CouchDBDatastore newInstance(DataBaseURL databaseURL, String userName, String password, boolean createDatabase) {
		RegisterBuiltin.register( ResteasyProviderFactory.getInstance() );

		CouchDBDatastore couchDBDatastore = new CouchDBDatastore( databaseURL, userName, password );

		if ( createDatabase ) {
			couchDBDatastore.createDatabase( databaseURL );
		}

		couchDBDatastore.createDesignDocumentsIfNotExist();

		return couchDBDatastore;
	}

	/**
	 * Saves a Document to the database
	 *
	 * @param document the {@link CouchDBDocument} to be saved
	 * @return the saved CouchDBDocument
	 */
	public CouchDBDocument saveDocument(CouchDBDocument document) {
		return doSaveDocument( document, false );
	}

	/**
	 * Saves the given design document in the database.
	 *
	 * @param design the design document to save
	 * @return the saved document
	 */
	public CouchDBDocument saveDocument(CouchDBDesignDocument design) {
		return doSaveDocument( design, true );
	}

	private CouchDBDocument doSaveDocument(CouchDBDocument document, boolean isDesignDocument) {
		Response response = null;
		try {
			if ( isDesignDocument ) {
				response = databaseClient.saveDesign( (CouchDBDesignDocument) document, document.getId() );
			}
			else {
				response = databaseClient.saveDocument( document, document.getId() );
			}
			if ( response.getStatus() == Response.Status.CREATED.getStatusCode() ) {
				CouchDBResponse entity = response.readEntity( CouchDBResponse.class );
				updateDocumentRevision( document, entity.getRev() );
			}
			else if ( response.getStatus() == Response.Status.CONFLICT.getStatusCode() ) {
				throw logger.getDocumentHasBeenConcurrentlyModifiedException( document.getId() );
			}
			else {
				CouchDBResponse entity = response.readEntity( CouchDBResponse.class );
				throw logger.errorCreatingDocument( response.getStatus(), entity.getError(), entity.getReason() );
			}
			return document;
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Retrieves a {@link CouchDBEntity} from the database
	 *
	 * @param id the id of the CouchDBEntity to retrieve
	 * @return the found CouchDBEntity or null
	 */
	public CouchDBEntity getEntity(String id) {
		Response response = null;
		try {
			response = databaseClient.getEntityById( id );
			if ( response.getStatus() == Response.Status.NOT_FOUND.getStatusCode() ) {
				return null;
			}
			else if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				return response.readEntity( CouchDBEntity.class );
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.errorRetrievingEntity( id, response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Returns the current revision of the document with the given id.
	 *
	 * @param id the document's id
	 * @return the current revision of the specified document or {@code null} if no document with the given id exists
	 */
	public String getCurrentRevision(String id) {
		Response response = null;

		try {
			response = databaseClient.getCurrentRevision( id );

			if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				//The revision is returned as ETag for HEAD requests
				return response.getEntityTag().getValue();
			}
			else if ( response.getStatus() == Response.Status.NOT_FOUND.getStatusCode() ) {
				return null;
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.errorRetrievingCurrentRevision( id, response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Retrieves a {@link CouchDBAssociation} from the database
	 *
	 * @param id the id of the CouchDBAssociation to retrieve
	 * @return the found CouchDBAssociation or null
	 */
	public CouchDBAssociation getAssociation(String id) {
		Response response = null;
		try {
			response = databaseClient.getAssociationById( id );
			if ( response.getStatus() == Response.Status.NOT_FOUND.getStatusCode() ) {
				return null;
			}
			else if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				return response.readEntity( CouchDBAssociation.class );
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.errorRetrievingAssociation( id, response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Retrieves all the tuples matching the {@link EntityKeyMetadata}
	 *
	 * @param entityKeyMetadata the EntityKeyMetadata used to filter the tuples
	 * @return all the tuples matching the given entityKeyMetadata
	 */
	public List<Tuple> getTuples(EntityKeyMetadata entityKeyMetadata) {
		final String tableName = getTableName( entityKeyMetadata );
		return getTuplesByTableName( tableName );
	}

	public long nextValue(RowKey key, int increment, int initialValue) {
		long value;
		try {
			CouchDBKeyValue identifier = getNextKeyValue( createId( key ), initialValue );
			value = identifier.getValue();
			saveIntegralIncreasedValue( increment, identifier );
		}
		catch (ResteasyClientException crf) {
			throw logger.errorCalculatingNextValue( crf );
		}
		return value;
	}

	/**
	 * Deletes a Document from the database
	 *
	 * @param id the id of the document to be deleted
	 * @param revision the revision of the document to be deleted
	 */
	public void deleteDocument(String id, String revision) {
		Response response = null;
		try {
			response = databaseClient.deleteDocument( id, revision );
			if ( response.getStatus() == Response.Status.CONFLICT.getStatusCode() ) {
				throw logger.getDocumentHasBeenConcurrentlyModifiedException( id );
			}
			else if ( response.getStatus() != Response.Status.OK.getStatusCode() &&  response.getStatus() != Response.Status.NOT_FOUND.getStatusCode() ) {
				throw logger.errorDeletingDocument( response.getStatus(), null, null );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Retrieves the number of CouchDBAssociation stored in the database
	 *
	 * @return the number of CouchDBAssociation stored in the database
	 */
	public int getNumberOfAssociations() {
		Response response = null;
		try {
			response = databaseClient.getNumberOfAssociations();
			if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				return response.readEntity( CouchDBCountResponse.class ).getCount();
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.unableToRetrieveTheNumberOfAssociations( response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Retrieves the number of CouchDBEntity stored in the database
	 *
	 * @return the number of CouchDBEntity stored in the database
	 */
	public int getNumberOfEntities() {
		Response response = null;
		try {
			response = databaseClient.getNumberOfEntities();
			if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				return response.readEntity( CouchDBCountResponse.class ).getCount();
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.unableToRetrieveTheNumberOfEntities( response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Deletes the database
	 */
	public void dropDatabase() {
		Response response = null;
		try {
			response = databaseClient.dropDatabase();
			if ( response.getStatus() != Response.Status.OK.getStatusCode() ) {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.errorDroppingDatabase( response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	/**
	 * Releases all the resources
	 */
	public void shutDown() {
	}

	private void createDatabase(DataBaseURL url) {
		Response response = null;
		try {
			if ( !databaseExists( url.getDataBaseName() ) ) {
				response = serverClient.createDatabase( url.getDataBaseName() );
				if ( response.getStatus() != Response.Status.CREATED.getStatusCode() ) {
					CouchDBResponse entity = response.readEntity( CouchDBResponse.class );
					throw logger.errorCreatingDatabase( url.getDataBaseName(), response.getStatus(), entity.getError(), entity.getReason() );
				}
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	private boolean databaseExists(String databaseName) {
		Response response = null;
		try {
			response = serverClient.getAllDatabaseNames();
			if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				@SuppressWarnings("unchecked")
				List<String> entity = response.readEntity( List.class );
				if ( entity.contains( databaseName ) ) {
					return true;
				}
				return false;
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.unableToRetrieveTheListOfDatabase( response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ProcessingException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	private List<Tuple> getTuplesByTableName(String tableName) {
		Response response = null;
		try {
			response = databaseClient.getEntityTuplesByTableName( "\"" + tableName + "\"" );
			if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				return response.readEntity( EntityTupleRows.class ).getTuples();
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.unableToRetrieveTheTupleByEntityKeyMetadata( tableName, response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	private void saveIntegralIncreasedValue(int increment, CouchDBKeyValue identifier) {
		identifier.increase( increment );
		saveDocument( identifier );
	}

	private String getTableName(EntityKeyMetadata entityKeyMetadata) {
		return entityKeyMetadata.getTable();
	}

	private ServerClient createServerClient(DataBaseURL databaseUrl) {
		ResteasyClient client = new ResteasyClientBuilder().build();
		ResteasyWebTarget target = client.target( databaseUrl.getServerUrl() );
		ServerClient serverClient = target.proxy( ServerClient.class );
		return serverClient;
	}

	private DatabaseClient createDataBaseClient(DataBaseURL databaseUrl) {
		Client client = ClientBuilder.newBuilder().build();
		WebTarget target = client.target( databaseUrl.toString() );
		ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
		DatabaseClient dbClient = rtarget.proxy( DatabaseClient.class );
		return dbClient;
	}

	private void updateDocumentRevision(CouchDBDocument document, String revision) {
		document.setRevision( revision );
	}

	private String createId(RowKey key) {
		StringBuilder builder = new StringBuilder( key.getTable() );
		builder.append( ":" );
		for ( int i = 0; i < key.getColumnNames().length; i++ ) {
			builder.append( key.getColumnNames()[i] );
		}
		builder.append( ":" );
		for ( int i = 0; i < key.getColumnValues().length; i++ ) {
			builder.append( key.getColumnValues()[i] );
		}
		return builder.toString();
	}

	private CouchDBKeyValue getNextKeyValue(String id, int initialValue) {
		Response response = null;
		try {
			response = databaseClient.getKeyValueById( id );
			if ( response.getStatus() == Response.Status.OK.getStatusCode() ) {
				return response.readEntity( CouchDBKeyValue.class );
			}
			else if ( response.getStatus() == Response.Status.NOT_FOUND.getStatusCode() ) {
				CouchDBKeyValue identifier = new CouchDBKeyValue( initialValue );
				identifier.setId( id );
				return identifier;
			}
			else {
				CouchDBResponse responseEntity = response.readEntity( CouchDBResponse.class );
				throw logger.errorRetrievingKeyValue( response.getStatus(), responseEntity.getError(), responseEntity.getReason() );
			}
		}
		catch (ResteasyClientException e) {
			throw logger.couchDBConnectionProblem( e );
		}
		finally {
			if ( response != null ) {
				response.close();
			}
		}
	}

	private void createDesignDocumentsIfNotExist() {
		if ( !designDocumentsExist() ) {
			createDesignDocuments();
		}
	}

	private void createDesignDocuments() {
		saveDocument( new EntitiesDesignDocument() );
		saveDocument( new AssociationsDesignDocument() );
		saveDocument( new TuplesDesignDocument() );
	}

	private boolean designDocumentsExist() {
		try {
			getNumberOfEntities();
			getNumberOfAssociations();
			return true;
		}
		catch (HibernateException e) {
			return false;
		}
	}

}
