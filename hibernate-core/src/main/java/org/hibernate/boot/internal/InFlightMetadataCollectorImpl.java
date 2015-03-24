/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2014, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.boot.internal;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.MapsId;

import org.hibernate.AnnotationException;
import org.hibernate.AssertionFailure;
import org.hibernate.DuplicateMappingException;
import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.AnyMetaDef;
import org.hibernate.annotations.common.reflection.XClass;
import org.hibernate.annotations.common.util.StringHelper;
import org.hibernate.boot.CacheRegionDefinition;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.SessionFactoryBuilder;
import org.hibernate.boot.model.IdentifierGeneratorDefinition;
import org.hibernate.boot.model.TypeDefinition;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.ImplicitForeignKeyNameSource;
import org.hibernate.boot.model.naming.ImplicitIndexNameSource;
import org.hibernate.boot.model.naming.ImplicitUniqueKeyNameSource;
import org.hibernate.boot.model.relational.AuxiliaryDatabaseObject;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.ExportableProducer;
import org.hibernate.boot.model.relational.Schema;
import org.hibernate.boot.model.source.internal.ConstraintSecondPass;
import org.hibernate.boot.model.source.internal.ImplicitColumnNamingSecondPass;
import org.hibernate.boot.model.source.spi.LocalMetadataBuildingContext;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.boot.spi.MetadataBuildingOptions;
import org.hibernate.boot.spi.NaturalIdUniqueKeyBinder;
import org.hibernate.cfg.AnnotatedClassType;
import org.hibernate.cfg.AttributeConverterDefinition;
import org.hibernate.cfg.CopyIdentifierComponentSecondPass;
import org.hibernate.cfg.CreateKeySecondPass;
import org.hibernate.cfg.FkSecondPass;
import org.hibernate.cfg.JPAIndexHolder;
import org.hibernate.cfg.PkDrivenByDefaultMapsIdSecondPass;
import org.hibernate.cfg.PropertyData;
import org.hibernate.cfg.QuerySecondPass;
import org.hibernate.cfg.RecoverableException;
import org.hibernate.cfg.SecondPass;
import org.hibernate.cfg.SecondaryTableSecondPass;
import org.hibernate.cfg.SetSimpleValueTypeSecondPass;
import org.hibernate.cfg.UniqueConstraintHolder;
import org.hibernate.cfg.annotations.NamedEntityGraphDefinition;
import org.hibernate.cfg.annotations.NamedProcedureCallDefinition;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.function.SQLFunction;
import org.hibernate.engine.ResultSetMappingDefinition;
import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.engine.spi.NamedQueryDefinition;
import org.hibernate.engine.spi.NamedSQLQueryDefinition;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.id.factory.IdentifierGeneratorFactory;
import org.hibernate.id.factory.spi.MutableIdentifierGeneratorFactory;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.NamedQueryRepository;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.DenormalizedTable;
import org.hibernate.mapping.FetchProfile;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.IdentifierCollection;
import org.hibernate.mapping.Index;
import org.hibernate.mapping.Join;
import org.hibernate.mapping.KeyValue;
import org.hibernate.mapping.MappedSuperclass;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.RootClass;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UniqueKey;
import org.hibernate.type.TypeResolver;

/**
 * The implementation of the in-flight Metadata collector contract.
 *
 * The usage expectation is that this class is used until all Metadata info is
 * collected and then {@link #buildMetadataInstance} is called to generate
 * the complete (and immutable) Metadata object.
 *
 * @author Steve Ebersole
 */
public class InFlightMetadataCollectorImpl implements InFlightMetadataCollector {
	private static final CoreMessageLogger log = CoreLogging.messageLogger( InFlightMetadataCollectorImpl.class );

	private final MetadataBuildingOptions options;
	private final TypeResolver typeResolver;

	private final UUID uuid;
	private final MutableIdentifierGeneratorFactory identifierGeneratorFactory;

	private final Map<String,PersistentClass> entityBindingMap = new HashMap<String, PersistentClass>();
	private final Map<String,Collection> collectionBindingMap = new HashMap<String,Collection>();

	private final Map<String, TypeDefinition> typeDefinitionMap = new HashMap<String, TypeDefinition>();
	private final Map<String, FilterDefinition> filterDefinitionMap = new HashMap<String, FilterDefinition>();
	private final Map<String, String> imports = new HashMap<String, String>();

	private Database database;

	private final Map<String, NamedQueryDefinition> namedQueryMap = new HashMap<String, NamedQueryDefinition>();
	private final Map<String, NamedSQLQueryDefinition> namedNativeQueryMap = new HashMap<String, NamedSQLQueryDefinition>();
	private final Map<String, NamedProcedureCallDefinition> namedProcedureCallMap = new HashMap<String, NamedProcedureCallDefinition>();
	private final Map<String, ResultSetMappingDefinition> sqlResultSetMappingMap = new HashMap<String, ResultSetMappingDefinition>();

	private final Map<String, NamedEntityGraphDefinition> namedEntityGraphMap = new HashMap<String, NamedEntityGraphDefinition>();
	private final Map<String, FetchProfile> fetchProfileMap = new HashMap<String, FetchProfile>();
	private final Map<String, IdentifierGeneratorDefinition> idGeneratorDefinitionMap = new HashMap<String, IdentifierGeneratorDefinition>();

	private Map<Class, AttributeConverterDefinition> attributeConverterDefinitionsByClass;
	private Map<String, SQLFunction> sqlFunctionMap;


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// All the annotation-processing-specific state :(
	private final Set<String> defaultIdentifierGeneratorNames = new HashSet<String>();
	private final Set<String> defaultNamedQueryNames = new HashSet<String>();
	private final Set<String> defaultNamedNativeQueryNames = new HashSet<String>();
	private final Set<String> defaultSqlResultSetMappingNames = new HashSet<String>();
	private final Set<String> defaultNamedProcedureNames = new HashSet<String>();
	private Map<String, AnyMetaDef> anyMetaDefs;
	private Map<Class, MappedSuperclass> mappedSuperClasses;
	private Map<XClass, Map<String, PropertyData>> propertiesAnnotatedWithMapsId;
	private Map<XClass, Map<String, PropertyData>> propertiesAnnotatedWithIdAndToOne;
	private Map<String, String> mappedByResolver;
	private Map<String, String> propertyRefResolver;
	private Set<DelayedPropertyReferenceHandler> delayedPropertyReferenceHandlers;
	private Map<Table, List<UniqueConstraintHolder>> uniqueConstraintHoldersByTable;
	private Map<Table, List<JPAIndexHolder>> jpaIndexHoldersByTable;

	public InFlightMetadataCollectorImpl(
			MetadataBuildingOptions options,
			MetadataSources sources,
			TypeResolver typeResolver) {
		this.uuid = UUID.randomUUID();
		this.options = options;
		this.typeResolver = typeResolver;

		this.identifierGeneratorFactory = options.getServiceRegistry().getService( MutableIdentifierGeneratorFactory.class );

		for ( AttributeConverterDefinition attributeConverterDefinition : sources.getAttributeConverters() ) {
			addAttributeConverter( attributeConverterDefinition );
		}

		for ( Map.Entry<String, SQLFunction> sqlFunctionEntry : sources.getSqlFunctions().entrySet() ) {
			if ( sqlFunctionMap == null ) {
				sqlFunctionMap = new ConcurrentHashMap<String, SQLFunction>( 16, .75f, 1 );
			}
			sqlFunctionMap.put( sqlFunctionEntry.getKey(), sqlFunctionEntry.getValue() );
		}

		for ( AuxiliaryDatabaseObject auxiliaryDatabaseObject : sources.getAuxiliaryDatabaseObjectList() ) {
			getDatabase().addAuxiliaryDatabaseObject( auxiliaryDatabaseObject );
		}

	}

	@Override
	public UUID getUUID() {
		return null;
	}

	@Override
	public MetadataBuildingOptions getMetadataBuildingOptions() {
		return options;
	}

	@Override
	public TypeResolver getTypeResolver() {
		return typeResolver;
	}

	@Override
	public Database getDatabase() {
		// important to delay this instantiation until as late as possible.
		if ( database == null ) {
			this.database = new Database( options );
		}
		return database;
	}

	@Override
	public NamedQueryRepository buildNamedQueryRepository(SessionFactoryImpl sessionFactory) {
		throw new UnsupportedOperationException( "#buildNamedQueryRepository should not be called on InFlightMetadataCollector" );
	}

	@Override
	public Map<String, SQLFunction> getSqlFunctionMap() {
		return sqlFunctionMap;
	}

	@Override
	public void validate() throws MappingException {
		// nothing to do
	}

	@Override
	public Set<MappedSuperclass> getMappedSuperclassMappingsCopy() {
		return new HashSet<MappedSuperclass>( mappedSuperClasses.values() );
	}

	@Override
	public IdentifierGeneratorFactory getIdentifierGeneratorFactory() {
		return identifierGeneratorFactory;
	}

	@Override
	public SessionFactoryBuilder getSessionFactoryBuilder() {
		throw new UnsupportedOperationException(
				"You should not be building a SessionFactory from an in-flight metadata collector; and of course " +
						"we should better segment this in the API :)"
		);
	}

	@Override
	public SessionFactory buildSessionFactory() {
		throw new UnsupportedOperationException(
				"You should not be building a SessionFactory from an in-flight metadata collector; and of course " +
						"we should better segment this in the API :)"
		);
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Entity handling

	@Override
	public java.util.Collection<PersistentClass> getEntityBindings() {
		return entityBindingMap.values();
	}

	@Override
	public Map<String, PersistentClass> getEntityBindingMap() {
		return entityBindingMap;
	}

	@Override
	public PersistentClass getEntityBinding(String entityName) {
		return entityBindingMap.get( entityName );
	}

	@Override
	public void addEntityBinding(PersistentClass persistentClass) throws DuplicateMappingException {
		final String entityName = persistentClass.getEntityName();
		if ( entityBindingMap.containsKey( entityName ) ) {
			throw new DuplicateMappingException( DuplicateMappingException.Type.ENTITY, entityName );
		}
		entityBindingMap.put( entityName, persistentClass );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Collection handling

	@Override
	public java.util.Collection<Collection> getCollectionBindings() {
		return collectionBindingMap.values();
	}

	@Override
	public Collection getCollectionBinding(String role) {
		return collectionBindingMap.get( role );
	}

	@Override
	public void addCollectionBinding(Collection collection) throws DuplicateMappingException {
		final String collectionRole = collection.getRole();
		if ( collectionBindingMap.containsKey( collectionRole ) ) {
			throw new DuplicateMappingException( DuplicateMappingException.Type.COLLECTION, collectionRole );
		}
		collectionBindingMap.put( collectionRole, collection );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Hibernate Type handling

	@Override
	public TypeDefinition getTypeDefinition(String registrationKey) {
		return typeDefinitionMap.get( registrationKey );
	}

	@Override
	public void addTypeDefinition(TypeDefinition typeDefinition) {
		if ( typeDefinition == null ) {
			throw new IllegalArgumentException( "Type definition is null" );
		}

		// Need to register both by name and registration keys.
		if ( !StringHelper.isEmpty( typeDefinition.getName() ) ) {
			addTypeDefinition( typeDefinition.getName(), typeDefinition );
		}

		if ( typeDefinition.getRegistrationKeys() != null ) {
			for ( String registrationKey : typeDefinition.getRegistrationKeys() ) {
				addTypeDefinition( registrationKey, typeDefinition );
			}
		}
	}

	private void addTypeDefinition(String registrationKey, TypeDefinition typeDefinition) {
		final TypeDefinition previous = typeDefinitionMap.put(
				registrationKey, typeDefinition );
		if ( previous != null ) {
			log.debugf(
					"Duplicate typedef name [%s] now -> %s",
					registrationKey,
					typeDefinition.getTypeImplementorClass().getName()
			);
		}
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// attribute converters

	@Override
	public void addAttributeConverter(AttributeConverterDefinition definition) {
		if ( attributeConverterDefinitionsByClass == null ) {
			attributeConverterDefinitionsByClass = new ConcurrentHashMap<Class, AttributeConverterDefinition>();
		}

		final Object old = attributeConverterDefinitionsByClass.put(
				definition.getAttributeConverter().getClass(),
				definition
		);

		if ( old != null ) {
			throw new AssertionFailure(
					String.format(
							Locale.ENGLISH,
							"AttributeConverter class [%s] registered multiple times",
							definition.getAttributeConverter().getClass()
					)
			);
		}
	}

	public static AttributeConverter instantiateAttributeConverter(Class<? extends AttributeConverter> attributeConverterClass) {
		try {
			return attributeConverterClass.newInstance();
		}
		catch (Exception e) {
			throw new AnnotationException(
					"Unable to instantiate AttributeConverter [" + attributeConverterClass.getName() + "]",
					e
			);
		}
	}

	public static AttributeConverterDefinition toAttributeConverterDefinition(AttributeConverter attributeConverter) {
		boolean autoApply = false;
		Converter converterAnnotation = attributeConverter.getClass().getAnnotation( Converter.class );
		if ( converterAnnotation != null ) {
			autoApply = converterAnnotation.autoApply();
		}

		return new AttributeConverterDefinition( attributeConverter, autoApply );
	}

	@Override
	public void addAttributeConverter(Class<? extends AttributeConverter> converterClass) {
		addAttributeConverter(
				toAttributeConverterDefinition(
						instantiateAttributeConverter( converterClass )
				)
		);
	}

	@Override
	public java.util.Collection<AttributeConverterDefinition> getAttributeConverters() {
		if ( attributeConverterDefinitionsByClass == null ) {
			return Collections.emptyList();
		}
		return attributeConverterDefinitionsByClass.values();
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// filter definitions

	@Override
	public Map<String, FilterDefinition> getFilterDefinitions() {
		return filterDefinitionMap;
	}

	@Override
	public FilterDefinition getFilterDefinition(String name) {
		return filterDefinitionMap.get( name );
	}

	@Override
	public void addFilterDefinition(FilterDefinition filterDefinition) {
		if ( filterDefinition == null || filterDefinition.getFilterName() == null ) {
			throw new IllegalArgumentException( "Filter definition object or name is null: "  + filterDefinition );
		}
		filterDefinitionMap.put( filterDefinition.getFilterName(), filterDefinition );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// fetch profiles

	@Override
	public java.util.Collection<FetchProfile> getFetchProfiles() {
		return fetchProfileMap.values();
	}

	@Override
	public FetchProfile getFetchProfile(String name) {
		return fetchProfileMap.get( name );
	}

	@Override
	public void addFetchProfile(FetchProfile profile) {
		if ( profile == null || profile.getName() == null ) {
			throw new IllegalArgumentException( "Fetch profile object or name is null: " + profile );
		}
		FetchProfile old = fetchProfileMap.put( profile.getName(), profile );
		if ( old != null ) {
			log.warn( "Duplicated fetch profile with same name [" + profile.getName() + "] found." );
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// identifier generators

	@Override
	public IdentifierGeneratorDefinition getIdentifierGenerator(String name) {
		if ( name == null ) {
			throw new IllegalArgumentException( "null is not a valid generator name" );
		}
		return idGeneratorDefinitionMap.get( name );
	}

	@Override
	public java.util.Collection<Table> collectTableMappings() {
		ArrayList<Table> tables = new ArrayList<Table>();
		for ( Schema schema : getDatabase().getSchemas() ) {
			tables.addAll( schema.getTables() );
		}
		return tables;
	}

	@Override
	public void addIdentifierGenerator(IdentifierGeneratorDefinition generator) {
		if ( generator == null || generator.getName() == null ) {
			throw new IllegalArgumentException( "ID generator object or name is null." );
		}

		if ( defaultIdentifierGeneratorNames.contains( generator.getName() ) ) {
			return;
		}

		final IdentifierGeneratorDefinition old = idGeneratorDefinitionMap.put( generator.getName(), generator );
		if ( old != null ) {
			log.duplicateGeneratorName( old.getName() );
		}
	}

	@Override
	public void addDefaultIdentifierGenerator(IdentifierGeneratorDefinition generator) {
		this.addIdentifierGenerator( generator );
		defaultIdentifierGeneratorNames.add( generator.getName() );
	}



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Named EntityGraph handling

	@Override
	public NamedEntityGraphDefinition getNamedEntityGraph(String name) {
		return namedEntityGraphMap.get( name );
	}

	@Override
	public Map<String, NamedEntityGraphDefinition> getNamedEntityGraphs() {
		return namedEntityGraphMap;
	}

	@Override
	public void addNamedEntityGraph(NamedEntityGraphDefinition definition) {
		final String name = definition.getRegisteredName();
		final NamedEntityGraphDefinition previous = namedEntityGraphMap.put( name, definition );
		if ( previous != null ) {
			throw new DuplicateMappingException(
					DuplicateMappingException.Type.NAMED_ENTITY_GRAPH, name );
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Named query handling

	public NamedQueryDefinition getNamedQueryDefinition(String name) {
		if ( name == null ) {
			throw new IllegalArgumentException( "null is not a valid query name" );
		}
		return namedQueryMap.get( name );
	}

	@Override
	public java.util.Collection<NamedQueryDefinition> getNamedQueryDefinitions() {
		return namedQueryMap.values();
	}

	@Override
	public void addNamedQuery(NamedQueryDefinition def) {
		if ( def == null ) {
			throw new IllegalArgumentException( "Named query definition is null" );
		}
		else if ( def.getName() == null ) {
			throw new IllegalArgumentException( "Named query definition name is null: " + def.getQueryString() );
		}

		if ( defaultNamedQueryNames.contains( def.getName() ) ) {
			return;
		}

		applyNamedQuery( def.getName(), def );
	}

	private void applyNamedQuery(String name, NamedQueryDefinition query) {
		checkQueryName( name );
		namedQueryMap.put( name.intern(), query );
	}

	private void checkQueryName(String name) throws DuplicateMappingException {
		if ( namedQueryMap.containsKey( name ) || namedNativeQueryMap.containsKey( name ) ) {
			throw new DuplicateMappingException( DuplicateMappingException.Type.QUERY, name );
		}
	}

	@Override
	public void addDefaultQuery(NamedQueryDefinition queryDefinition) {
		applyNamedQuery( queryDefinition.getName(), queryDefinition );
		defaultNamedQueryNames.add( queryDefinition.getName() );
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Named native-query handling

	@Override
	public NamedSQLQueryDefinition getNamedNativeQueryDefinition(String name) {
		return namedNativeQueryMap.get( name );
	}

	@Override
	public java.util.Collection<NamedSQLQueryDefinition> getNamedNativeQueryDefinitions() {
		return namedNativeQueryMap.values();
	}

	@Override
	public void addNamedNativeQuery(NamedSQLQueryDefinition def) {
		if ( def == null ) {
			throw new IllegalArgumentException( "Named native query definition object is null" );
		}
		if ( def.getName() == null ) {
			throw new IllegalArgumentException( "Named native query definition name is null: " + def.getQueryString() );
		}

		if ( defaultNamedNativeQueryNames.contains( def.getName() ) ) {
			return;
		}

		applyNamedNativeQuery( def.getName(), def );
	}

	private void applyNamedNativeQuery(String name, NamedSQLQueryDefinition query) {
		checkQueryName( name );
		namedNativeQueryMap.put( name.intern(), query );
	}

	@Override
	public void addDefaultNamedNativeQuery(NamedSQLQueryDefinition query) {
		applyNamedNativeQuery( query.getName(), query );
		defaultNamedNativeQueryNames.add( query.getName() );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Named stored-procedure handling

	@Override
	public java.util.Collection<NamedProcedureCallDefinition> getNamedProcedureCallDefinitions() {
		return namedProcedureCallMap.values();
	}

	@Override
	public void addNamedProcedureCallDefinition(NamedProcedureCallDefinition definition) {
		if ( definition == null ) {
			throw new IllegalArgumentException( "Named query definition is null" );
		}

		final String name = definition.getRegisteredName();

		if ( defaultNamedProcedureNames.contains( name ) ) {
			return;
		}

		final NamedProcedureCallDefinition previous = namedProcedureCallMap.put( name, definition );
		if ( previous != null ) {
			throw new DuplicateMappingException( DuplicateMappingException.Type.PROCEDURE, name );
		}
	}

	@Override
	public void addDefaultNamedProcedureCallDefinition(NamedProcedureCallDefinition definition) {
		addNamedProcedureCallDefinition( definition );
		defaultNamedProcedureNames.add( definition.getRegisteredName() );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// result-set mapping handling

	@Override
	public Map<String, ResultSetMappingDefinition> getResultSetMappingDefinitions() {
		return sqlResultSetMappingMap;
	}

	@Override
	public ResultSetMappingDefinition getResultSetMapping(String name) {
		return sqlResultSetMappingMap.get( name );
	}

	@Override
	public void addResultSetMapping(ResultSetMappingDefinition resultSetMappingDefinition) {
		if ( resultSetMappingDefinition == null ) {
			throw new IllegalArgumentException( "Result-set mapping was null" );
		}

		final String name = resultSetMappingDefinition.getName();
		if ( name == null ) {
			throw new IllegalArgumentException( "Result-set mapping name is null: " + resultSetMappingDefinition );
		}

		if ( defaultSqlResultSetMappingNames.contains( name ) ) {
			return;
		}

		applyResultSetMapping( resultSetMappingDefinition );
	}

	public void applyResultSetMapping(ResultSetMappingDefinition resultSetMappingDefinition) {
		final ResultSetMappingDefinition old = sqlResultSetMappingMap.put(
				resultSetMappingDefinition.getName(),
				resultSetMappingDefinition
		);
		if ( old != null ) {
			throw new DuplicateMappingException(
					DuplicateMappingException.Type.RESULT_SET_MAPPING,
					resultSetMappingDefinition.getName()
			);
		}
	}

	@Override
	public void addDefaultResultSetMapping(ResultSetMappingDefinition definition) {
		final String name = definition.getName();
		if ( !defaultSqlResultSetMappingNames.contains( name ) && sqlResultSetMappingMap.containsKey( name ) ) {
			sqlResultSetMappingMap.remove( name );
		}
		applyResultSetMapping( definition );
		defaultSqlResultSetMappingNames.add( name );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// imports

	@Override
	public Map<String,String> getImports() {
		return imports;
	}

	@Override
	public void addImport(String importName, String entityName) {
		if ( importName == null || entityName == null ) {
			throw new IllegalArgumentException( "Import name or entity name is null" );
		}
		log.tracev( "Import: {0} -> {1}", importName, entityName );
		String old = imports.put( importName, entityName );
		if ( old != null ) {
			log.debug( "import name [" + importName + "] overrode previous [{" + old + "}]" );
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Table handling

	@Override
	public Table addTable(
			String schemaName,
			String catalogName,
			String name,
			String subselectFragment,
			boolean isAbstract) {
		final Schema schema = getDatabase().locateSchema(
				getDatabase().toIdentifier( catalogName ),
				getDatabase().toIdentifier( schemaName )
		);

		// annotation binding depends on the "table name" for @Subselect bindings
		// being set into the generated table (mainly to avoid later NPE), but for now we need to keep that :(
		final Identifier logicalName;
		if ( name != null ) {
			logicalName = getDatabase().toIdentifier( name );
		}
		else {
			logicalName = null;
		}

		if ( subselectFragment != null ) {
			return new Table( schema, logicalName, subselectFragment, isAbstract );
		}
		else {
			Table table = schema.locateTable( logicalName );
			if ( table != null ) {
				if ( !isAbstract ) {
					table.setAbstract( false );
				}
				return table;
			}
			return schema.createTable( logicalName, isAbstract );
		}
	}

	@Override
	public Table addDenormalizedTable(
			String schemaName,
			String catalogName,
			String name,
			boolean isAbstract,
			String subselectFragment,
			Table includedTable) throws DuplicateMappingException {
		final Schema schema = getDatabase().locateSchema(
				getDatabase().toIdentifier( catalogName ),
				getDatabase().toIdentifier( schemaName )
		);

		// annotation binding depends on the "table name" for @Subselect bindings
		// being set into the generated table (mainly to avoid later NPE), but for now we need to keep that :(
		final Identifier logicalName;
		if ( name != null ) {
			logicalName = getDatabase().toIdentifier( name );
		}
		else {
			logicalName = null;
		}

		if ( subselectFragment != null ) {
			return new DenormalizedTable( schema, logicalName, subselectFragment, isAbstract, includedTable );
		}
		else {
			Table table = schema.locateTable( logicalName );
			if ( table != null ) {
				throw new DuplicateMappingException( DuplicateMappingException.Type.TABLE, logicalName.toString() );
			}
			else {
				table = schema.createDenormalizedTable( logicalName, isAbstract, includedTable );
			}
			return table;
		}
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Mapping impl

	@Override
	public org.hibernate.type.Type getIdentifierType(String entityName) throws MappingException {
		final PersistentClass pc = entityBindingMap.get( entityName );
		if ( pc == null ) {
			throw new MappingException( "persistent class not known: " + entityName );
		}
		return pc.getIdentifier().getType();
	}

	@Override
	public String getIdentifierPropertyName(String entityName) throws MappingException {
		final PersistentClass pc = entityBindingMap.get( entityName );
		if ( pc == null ) {
			throw new MappingException( "persistent class not known: " + entityName );
		}
		if ( !pc.hasIdentifierProperty() ) {
			return null;
		}
		return pc.getIdentifierProperty().getName();
	}

	@Override
	public org.hibernate.type.Type getReferencedPropertyType(String entityName, String propertyName) throws MappingException {
		final PersistentClass pc = entityBindingMap.get( entityName );
		if ( pc == null ) {
			throw new MappingException( "persistent class not known: " + entityName );
		}
		Property prop = pc.getReferencedProperty( propertyName );
		if ( prop == null ) {
			throw new MappingException(
					"property not known: " +
							entityName + '.' + propertyName
			);
		}
		return prop.getType();
	}


	private Map<Identifier,Identifier> logicalToPhysicalTableNameMap = new HashMap<Identifier, Identifier>();
	private Map<Identifier,Identifier> physicalToLogicalTableNameMap = new HashMap<Identifier, Identifier>();

	@Override
	public void addTableNameBinding(Identifier logicalName, Table table) {
		logicalToPhysicalTableNameMap.put( logicalName, table.getNameIdentifier() );
		physicalToLogicalTableNameMap.put( table.getNameIdentifier(), logicalName );
	}

	@Override
	public void addTableNameBinding(String schema, String catalog, String logicalName, String realTableName, Table denormalizedSuperTable) {
		final Identifier logicalNameIdentifier = getDatabase().toIdentifier( logicalName );
		final Identifier physicalNameIdentifier = getDatabase().toIdentifier( realTableName );

		logicalToPhysicalTableNameMap.put( logicalNameIdentifier, physicalNameIdentifier );
		physicalToLogicalTableNameMap.put( physicalNameIdentifier, logicalNameIdentifier );
	}

	@Override
	public String getLogicalTableName(Table ownerTable) {
		final Identifier logicalName = physicalToLogicalTableNameMap.get( ownerTable.getNameIdentifier() );
		if ( logicalName == null ) {
			throw new MappingException( "Unable to find physical table: " + ownerTable.getName() );
		}
		return logicalName.render();
	}

	@Override
	public String getPhysicalTableName(Identifier logicalName) {
		final Identifier physicalName = logicalToPhysicalTableNameMap.get( logicalName );
		return physicalName == null ? null : physicalName.render();
	}

	@Override
	public String getPhysicalTableName(String logicalName) {
		return getPhysicalTableName( getDatabase().toIdentifier( logicalName ) );
	}

	/**
	 * Internal struct used to maintain xref between physical and logical column
	 * names for a table.  Mainly this is used to ensure that the defined NamingStrategy
	 * is not creating duplicate column names.
	 */
	private class TableColumnNameBinding implements Serializable {
		private final String tableName;
		private Map<Identifier, String> logicalToPhysical = new HashMap<Identifier,String>();
		private Map<String, Identifier> physicalToLogical = new HashMap<String,Identifier>();

		private TableColumnNameBinding(String tableName) {
			this.tableName = tableName;
		}

		public void addBinding(Identifier logicalName, Column physicalColumn) {
			final String physicalNameString = physicalColumn.getQuotedName( getDatabase().getJdbcEnvironment().getDialect() );

			bindLogicalToPhysical( logicalName, physicalNameString );
			bindPhysicalToLogical( logicalName, physicalNameString );
		}

		private void bindLogicalToPhysical(Identifier logicalName, String physicalName) throws DuplicateMappingException {
			final String existingPhysicalNameMapping = logicalToPhysical.put( logicalName, physicalName );
			if ( existingPhysicalNameMapping != null ) {
				final boolean areSame = logicalName.isQuoted()
						? physicalName.equals( existingPhysicalNameMapping )
						: physicalName.equalsIgnoreCase( existingPhysicalNameMapping );
				if ( !areSame ) {
					throw new DuplicateMappingException(
							String.format(
									Locale.ENGLISH,
									"Table [%s] contains logical column name [%s] referring to multiple physical " +
											"column names: [%s], [%s]",
									tableName,
									logicalName,
									existingPhysicalNameMapping,
									physicalName
							),
							DuplicateMappingException.Type.COLUMN_BINDING,
							tableName + "." + logicalName
					);
				}
			}
		}

		private void bindPhysicalToLogical(Identifier logicalName, String physicalName) throws DuplicateMappingException {
			final Identifier existingLogicalName = physicalToLogical.put( physicalName, logicalName );
			if ( existingLogicalName != null && ! existingLogicalName.equals( logicalName ) ) {
				throw new DuplicateMappingException(
						String.format(
								Locale.ENGLISH,
								"Table [%s] contains physical column name [%s] referred to by multiple physical " +
										"column names: [%s], [%s]",
								tableName,
								physicalName,
								logicalName,
								existingLogicalName
						),
						DuplicateMappingException.Type.COLUMN_BINDING,
						tableName + "." + physicalName
				);
			}
		}
	}

	private Map<Table,TableColumnNameBinding> columnNameBindingByTableMap;

	@Override
	public void addColumnNameBinding(Table table, String logicalName, Column column) throws DuplicateMappingException {
		addColumnNameBinding( table, getDatabase().toIdentifier( logicalName ), column );
	}

	@Override
	public void addColumnNameBinding(Table table, Identifier logicalName, Column column) throws DuplicateMappingException {
		TableColumnNameBinding binding = null;

		if ( columnNameBindingByTableMap == null ) {
			columnNameBindingByTableMap = new HashMap<Table, TableColumnNameBinding>();
		}
		else {
			binding = columnNameBindingByTableMap.get( table );
		}

		if ( binding == null ) {
			binding = new TableColumnNameBinding( table.getName() );
			columnNameBindingByTableMap.put( table, binding );
		}

		binding.addBinding( logicalName, column );
	}

	@Override
	public String getPhysicalColumnName(Table table, String logicalName) throws MappingException {
		return getPhysicalColumnName( table, getDatabase().toIdentifier( logicalName ) );
	}

	@Override
	public String getPhysicalColumnName(Table table, Identifier logicalName) throws MappingException {
		if ( logicalName == null ) {
			throw new MappingException( "Logical column name cannot be null" );
		}

		Table currentTable = table;
		String physicalName = null;

		while ( currentTable != null ) {
			final TableColumnNameBinding binding = columnNameBindingByTableMap.get( currentTable );
			if ( binding != null ) {
				physicalName = binding.logicalToPhysical.get( logicalName );
				if ( physicalName != null ) {
					break;
				}
			}

			if ( DenormalizedTable.class.isInstance( currentTable ) ) {
				currentTable = ( (DenormalizedTable) currentTable ).getIncludedTable();
			}
			else {
				currentTable = null;
			}
		}

		if ( physicalName == null ) {
			throw new MappingException(
					"Unable to find column with logical name " + logicalName.render() + " in table " + table.getName()
			);
		}
		return physicalName;
	}

	@Override
	public String getLogicalColumnName(Table table, String physicalName) throws MappingException {
		return getLogicalColumnName( table, getDatabase().toIdentifier( physicalName ) );
	}


	@Override
	public String getLogicalColumnName(Table table, Identifier physicalName) throws MappingException {
		final String physicalNameString = physicalName.render( getDatabase().getJdbcEnvironment().getDialect() );
		Identifier logicalName = null;

		Table currentTable = table;
		while ( currentTable != null ) {
			final TableColumnNameBinding binding = columnNameBindingByTableMap.get( currentTable );

			if ( binding != null ) {
				logicalName = binding.physicalToLogical.get( physicalNameString );
				if ( logicalName != null ) {
					break;
				}
			}

			if ( DenormalizedTable.class.isInstance( currentTable ) ) {
				currentTable = ( (DenormalizedTable) currentTable ).getIncludedTable();
			}
			else {
				currentTable = null;
			}
		}

		if ( logicalName == null ) {
			throw new MappingException(
					"Unable to find column with physical name " + physicalNameString + " in table " + table.getName()
			);
		}
		return logicalName.render();
	}

	@Override
	public void addAuxiliaryDatabaseObject(AuxiliaryDatabaseObject auxiliaryDatabaseObject) {
		getDatabase().addAuxiliaryDatabaseObject( auxiliaryDatabaseObject );
	}

	private final Map<String,AnnotatedClassType> annotatedClassTypeMap = new HashMap<String, AnnotatedClassType>();

	@Override
	public AnnotatedClassType getClassType(XClass clazz) {
		AnnotatedClassType type = annotatedClassTypeMap.get( clazz.getName() );
		if ( type == null ) {
			return addClassType( clazz );
		}
		else {
			return type;
		}
	}

	@Override
	public AnnotatedClassType addClassType(XClass clazz) {
		AnnotatedClassType type;
		if ( clazz.isAnnotationPresent( Entity.class ) ) {
			type = AnnotatedClassType.ENTITY;
		}
		else if ( clazz.isAnnotationPresent( Embeddable.class ) ) {
			type = AnnotatedClassType.EMBEDDABLE;
		}
		else if ( clazz.isAnnotationPresent( javax.persistence.MappedSuperclass.class ) ) {
			type = AnnotatedClassType.EMBEDDABLE_SUPERCLASS;
		}
		else {
			type = AnnotatedClassType.NONE;
		}
		annotatedClassTypeMap.put( clazz.getName(), type );
		return type;
	}

	@Override
	public void addAnyMetaDef(AnyMetaDef defAnn) {
		if ( anyMetaDefs == null ) {
			anyMetaDefs = new HashMap<String, AnyMetaDef>();
		}
		else {
			if ( anyMetaDefs.containsKey( defAnn.name() ) ) {
				throw new AnnotationException( "Two @AnyMetaDef with the same name defined: " + defAnn.name() );
			}
		}

		anyMetaDefs.put( defAnn.name(), defAnn );
	}

	@Override
	public AnyMetaDef getAnyMetaDef(String name) {
		if ( anyMetaDefs == null ) {
			return null;
		}
		return anyMetaDefs.get( name );
	}


	@Override
	public void addMappedSuperclass(Class type, MappedSuperclass mappedSuperclass) {
		if ( mappedSuperClasses == null ) {
			mappedSuperClasses = new HashMap<Class, MappedSuperclass>();
		}
		mappedSuperClasses.put( type, mappedSuperclass );
	}

	@Override
	public MappedSuperclass getMappedSuperclass(Class type) {
		if ( mappedSuperClasses == null ) {
			return null;
		}
		return mappedSuperClasses.get( type );
	}

	@Override
	public PropertyData getPropertyAnnotatedWithMapsId(XClass entityType, String propertyName) {
		if ( propertiesAnnotatedWithMapsId == null ) {
			return null;
		}

		final Map<String, PropertyData> map = propertiesAnnotatedWithMapsId.get( entityType );
		return map == null ? null : map.get( propertyName );
	}

	@Override
	public void addPropertyAnnotatedWithMapsId(XClass entityType, PropertyData property) {
		if ( propertiesAnnotatedWithMapsId == null ) {
			propertiesAnnotatedWithMapsId = new HashMap<XClass, Map<String, PropertyData>>();
		}

		Map<String, PropertyData> map = propertiesAnnotatedWithMapsId.get( entityType );
		if ( map == null ) {
			map = new HashMap<String, PropertyData>();
			propertiesAnnotatedWithMapsId.put( entityType, map );
		}
		map.put( property.getProperty().getAnnotation( MapsId.class ).value(), property );
	}

	@Override
	public void addPropertyAnnotatedWithMapsIdSpecj(XClass entityType, PropertyData property, String mapsIdValue) {
		if ( propertiesAnnotatedWithMapsId == null ) {
			propertiesAnnotatedWithMapsId = new HashMap<XClass, Map<String, PropertyData>>();
		}

		Map<String, PropertyData> map = propertiesAnnotatedWithMapsId.get( entityType );
		if ( map == null ) {
			map = new HashMap<String, PropertyData>();
			propertiesAnnotatedWithMapsId.put( entityType, map );
		}
		map.put( mapsIdValue, property );
	}

	@Override
	public PropertyData getPropertyAnnotatedWithIdAndToOne(XClass entityType, String propertyName) {
		if ( propertiesAnnotatedWithIdAndToOne == null ) {
			return null;
		}

		final Map<String, PropertyData> map = propertiesAnnotatedWithIdAndToOne.get( entityType );
		return map == null ? null : map.get( propertyName );
	}

	@Override
	public void addToOneAndIdProperty(XClass entityType, PropertyData property) {
		if ( propertiesAnnotatedWithIdAndToOne == null ) {
			propertiesAnnotatedWithIdAndToOne = new HashMap<XClass, Map<String, PropertyData>>();
		}

		Map<String, PropertyData> map = propertiesAnnotatedWithIdAndToOne.get( entityType );
		if ( map == null ) {
			map = new HashMap<String, PropertyData>();
			propertiesAnnotatedWithIdAndToOne.put( entityType, map );
		}
		map.put( property.getPropertyName(), property );
	}

	@Override
	public void addMappedBy(String entityName, String propertyName, String inversePropertyName) {
		if ( mappedByResolver == null ) {
			mappedByResolver = new HashMap<String, String>();
		}
		mappedByResolver.put( entityName + "." + propertyName, inversePropertyName );
	}

	@Override
	public String getFromMappedBy(String entityName, String propertyName) {
		if ( mappedByResolver == null ) {
			return null;
		}
		return mappedByResolver.get( entityName + "." + propertyName );
	}

	@Override
	public void addPropertyReferencedAssociation(String entityName, String propertyName, String propertyRef) {
		if ( propertyRefResolver == null ) {
			propertyRefResolver = new HashMap<String, String>();
		}
		propertyRefResolver.put( entityName + "." + propertyName, propertyRef );
	}

	@Override
	public String getPropertyReferencedAssociation(String entityName, String propertyName) {
		if ( propertyRefResolver == null ) {
			return null;
		}
		return propertyRefResolver.get( entityName + "." + propertyName );
	}

	private static class DelayedPropertyReferenceHandlerAnnotationImpl implements DelayedPropertyReferenceHandler {
		public final String referencedClass;
		public final String propertyName;
		public final boolean unique;

		public DelayedPropertyReferenceHandlerAnnotationImpl(String referencedClass, String propertyName, boolean unique) {
			this.referencedClass = referencedClass;
			this.propertyName = propertyName;
			this.unique = unique;
		}

		@Override
		public void process(InFlightMetadataCollector metadataCollector) {
			final PersistentClass clazz = metadataCollector.getEntityBinding( referencedClass );
			if ( clazz == null ) {
				throw new MappingException( "property-ref to unmapped class: " + referencedClass );
			}

			final Property prop = clazz.getReferencedProperty( propertyName );
			if ( unique ) {
				( (SimpleValue) prop.getValue() ).setAlternateUniqueKey( true );
			}
		}
	}

	@Override
	public void addPropertyReference(String referencedClass, String propertyName) {
		addDelayedPropertyReferenceHandler(
				new DelayedPropertyReferenceHandlerAnnotationImpl( referencedClass, propertyName, false )
		);
	}

	@Override
	public void addDelayedPropertyReferenceHandler(DelayedPropertyReferenceHandler handler) {
		if ( delayedPropertyReferenceHandlers == null ) {
			delayedPropertyReferenceHandlers = new HashSet<DelayedPropertyReferenceHandler>();
		}
		delayedPropertyReferenceHandlers.add( handler );
	}

	@Override
	public void addUniquePropertyReference(String referencedClass, String propertyName) {
		addDelayedPropertyReferenceHandler(
				new DelayedPropertyReferenceHandlerAnnotationImpl( referencedClass, propertyName, true )
		);
	}

	@Override
	@SuppressWarnings({ "unchecked" })
	public void addUniqueConstraints(Table table, List uniqueConstraints) {
		List<UniqueConstraintHolder> constraintHolders = new ArrayList<UniqueConstraintHolder>(
				CollectionHelper.determineProperSizing( uniqueConstraints.size() )
		);

		int keyNameBase = determineCurrentNumberOfUniqueConstraintHolders( table );
		for ( String[] columns : ( List<String[]> ) uniqueConstraints ) {
			final String keyName = "key" + keyNameBase++;
			constraintHolders.add(
					new UniqueConstraintHolder().setName( keyName ).setColumns( columns )
			);
		}
		addUniqueConstraintHolders( table, constraintHolders );
	}

	private int determineCurrentNumberOfUniqueConstraintHolders(Table table) {
		List currentHolders = uniqueConstraintHoldersByTable == null ? null : uniqueConstraintHoldersByTable.get( table );
		return currentHolders == null
				? 0
				: currentHolders.size();
	}

	@Override
	public void addUniqueConstraintHolders(Table table, List<UniqueConstraintHolder> uniqueConstraintHolders) {
		List<UniqueConstraintHolder> holderList = null;

		if ( uniqueConstraintHoldersByTable == null ) {
			uniqueConstraintHoldersByTable = new HashMap<Table, List<UniqueConstraintHolder>>();
		}
		else {
			holderList = uniqueConstraintHoldersByTable.get( table );
		}

		if ( holderList == null ) {
			holderList = new ArrayList<UniqueConstraintHolder>();
			uniqueConstraintHoldersByTable.put( table, holderList );
		}

		holderList.addAll( uniqueConstraintHolders );
	}

	@Override
	public void addJpaIndexHolders(Table table, List<JPAIndexHolder> holders) {
		List<JPAIndexHolder> holderList = null;

		if ( jpaIndexHoldersByTable == null ) {
			jpaIndexHoldersByTable = new HashMap<Table, List<JPAIndexHolder>>();
		}
		else {
			holderList = jpaIndexHoldersByTable.get( table );
		}

		if ( holderList == null ) {
			holderList = new ArrayList<JPAIndexHolder>();
			jpaIndexHoldersByTable.put( table, holderList );
		}

		holderList.addAll( holders );
	}

	private final Map<String,EntityTableXrefImpl> entityTableXrefMap = new HashMap<String, EntityTableXrefImpl>();

	@Override
	public EntityTableXref getEntityTableXref(String entityName) {
		return entityTableXrefMap.get( entityName );
	}

	@Override
	public EntityTableXref addEntityTableXref(
			String entityName,
			Identifier primaryTableLogicalName,
			Table primaryTable,
			EntityTableXref superEntityTableXref) {
		final EntityTableXrefImpl entry = new EntityTableXrefImpl(
				primaryTableLogicalName,
				primaryTable,
				(EntityTableXrefImpl) superEntityTableXref
		);

		entityTableXrefMap.put( entityName, entry );

		return entry;
	}

	@Override
	public Map<String, Join> getJoins(String entityName) {
		EntityTableXrefImpl xrefEntry = entityTableXrefMap.get( entityName );
		return xrefEntry == null ? null : xrefEntry.secondaryTableJoinMap;
	}

	@Override
	public void addJoins(PersistentClass persistentClass, Map<String, Join> joins) {
		// this part about resolving the super-EntityTableXref is a best effort.  But
		// really annotation binding is the only thing calling this, and it does not
		// directly use the EntityTableXref stuff, so its all good.
		final EntityTableXrefImpl superEntityTableXref;
		if ( persistentClass.getSuperclass() == null ) {
			superEntityTableXref = null;
		}
		else {
			superEntityTableXref = entityTableXrefMap.get( persistentClass.getSuperclass().getEntityName() );
		}

		final String primaryTableLogicalName = getLogicalTableName( persistentClass.getTable() );
		final EntityTableXrefImpl xrefEntry = new EntityTableXrefImpl(
				getDatabase().toIdentifier( primaryTableLogicalName ),
				persistentClass.getTable(),
				superEntityTableXref
		);

		final EntityTableXrefImpl old = entityTableXrefMap.put( persistentClass.getEntityName(), xrefEntry );
		if ( old != null ) {
			log.duplicateJoins( persistentClass.getEntityName() );
		}

		xrefEntry.secondaryTableJoinMap = joins;
	}

	private final class EntityTableXrefImpl implements EntityTableXref {
		private final Identifier primaryTableLogicalName;
		private final Table primaryTable;
		private EntityTableXrefImpl superEntityTableXref;

		//annotations needs a Map<String,Join>
		//private Map<Identifier,Join> secondaryTableJoinMap;
		private Map<String,Join> secondaryTableJoinMap;

		public EntityTableXrefImpl(Identifier primaryTableLogicalName, Table primaryTable, EntityTableXrefImpl superEntityTableXref) {
			this.primaryTableLogicalName = primaryTableLogicalName;
			this.primaryTable = primaryTable;
			this.superEntityTableXref = superEntityTableXref;
		}

		@Override
		public void addSecondaryTable(LocalMetadataBuildingContext buildingContext, Identifier logicalName, Join secondaryTableJoin) {
			if ( Identifier.areEqual( primaryTableLogicalName, logicalName ) ) {
				throw new org.hibernate.boot.MappingException(
						String.format(
								Locale.ENGLISH,
								"Attempt to add secondary table with same name as primary table [%s]",
								primaryTableLogicalName
						),
						buildingContext.getOrigin()
				);
			}


			if ( secondaryTableJoinMap == null ) {
				//secondaryTableJoinMap = new HashMap<Identifier,Join>();
				//secondaryTableJoinMap.put( logicalName, secondaryTableJoin );
				secondaryTableJoinMap = new HashMap<String,Join>();
				secondaryTableJoinMap.put( logicalName.getCanonicalName(), secondaryTableJoin );
			}
			else {
				//final Join existing = secondaryTableJoinMap.put( logicalName, secondaryTableJoin );
				final Join existing = secondaryTableJoinMap.put( logicalName.getCanonicalName(), secondaryTableJoin );

				if ( existing != null ) {
					throw new org.hibernate.boot.MappingException(
							String.format(
									Locale.ENGLISH,
									"Added secondary table with same name [%s]",
									logicalName
							),
							buildingContext.getOrigin()
					);
				}
			}
		}

		@Override
		public void addSecondaryTable(Identifier logicalName, Join secondaryTableJoin) {
			if ( Identifier.areEqual( primaryTableLogicalName, logicalName ) ) {
				throw new DuplicateSecondaryTableException( logicalName );
			}


			if ( secondaryTableJoinMap == null ) {
				//secondaryTableJoinMap = new HashMap<Identifier,Join>();
				//secondaryTableJoinMap.put( logicalName, secondaryTableJoin );
				secondaryTableJoinMap = new HashMap<String,Join>();
				secondaryTableJoinMap.put( logicalName.getCanonicalName(), secondaryTableJoin );
			}
			else {
				//final Join existing = secondaryTableJoinMap.put( logicalName, secondaryTableJoin );
				final Join existing = secondaryTableJoinMap.put( logicalName.getCanonicalName(), secondaryTableJoin );

				if ( existing != null ) {
					throw new DuplicateSecondaryTableException( logicalName );
				}
			}
		}

		@Override
		public Table getPrimaryTable() {
			return primaryTable;
		}

		@Override
		public Table resolveTable(Identifier tableName) {
			if ( tableName == null ) {
				return primaryTable;
			}

			if ( Identifier.areEqual( primaryTableLogicalName, tableName ) ) {
				return primaryTable;
			}

			Join secondaryTableJoin = null;
			if ( secondaryTableJoinMap != null ) {
				//secondaryTableJoin = secondaryTableJoinMap.get( tableName );
				secondaryTableJoin = secondaryTableJoinMap.get( tableName.getCanonicalName() );
			}

			if ( secondaryTableJoin != null ) {
				return secondaryTableJoin.getTable();
			}

			if ( superEntityTableXref != null ) {
				return superEntityTableXref.resolveTable( tableName );
			}

			return null;
		}

		public Join locateJoin(Identifier tableName) {
			if ( tableName == null ) {
				return null;
			}

			Join join = null;
			if ( secondaryTableJoinMap != null ) {
				join = secondaryTableJoinMap.get( tableName.getCanonicalName() );
			}

			if ( join != null ) {
				return join;
			}

			if ( superEntityTableXref != null ) {
				return superEntityTableXref.locateJoin( tableName );
			}

			return null;
		}
	}

	private ArrayList<PkDrivenByDefaultMapsIdSecondPass> pkDrivenByDefaultMapsId_secondPassList;
	private ArrayList<SetSimpleValueTypeSecondPass> setSimpleValueType_secondPassList;
	private ArrayList<CopyIdentifierComponentSecondPass> copyIdentifierComponent_secondPasList;
	private ArrayList<FkSecondPass> fk_secondPassList;
	private ArrayList<CreateKeySecondPass> createKey_secondPasList;
	private ArrayList<SecondaryTableSecondPass> secondaryTable_secondPassList;
	private ArrayList<QuerySecondPass> query_secondPassList;
	private ArrayList<ConstraintSecondPass> constraint_secondPassList;
	private ArrayList<ImplicitColumnNamingSecondPass> implicitColumnNaming_secondPassList;

	private ArrayList<SecondPass> general_secondPassList;

	@Override
	public void addSecondPass(SecondPass secondPass) {
		addSecondPass( secondPass, false );
	}

	@Override
	public void addSecondPass(SecondPass secondPass, boolean onTopOfTheQueue) {
		if ( secondPass instanceof PkDrivenByDefaultMapsIdSecondPass ) {
			addPkDrivenByDefaultMapsIdSecondPass( (PkDrivenByDefaultMapsIdSecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof SetSimpleValueTypeSecondPass ) {
			addSetSimpleValueTypeSecondPass( (SetSimpleValueTypeSecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof CopyIdentifierComponentSecondPass ) {
			addCopyIdentifierComponentSecondPass( (CopyIdentifierComponentSecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof FkSecondPass ) {
			addFkSecondPass( (FkSecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof CreateKeySecondPass ) {
			addCreateKeySecondPass( (CreateKeySecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof SecondaryTableSecondPass ) {
			addSecondaryTableSecondPass( (SecondaryTableSecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof QuerySecondPass ) {
			addQuerySecondPass( (QuerySecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof ConstraintSecondPass ) {
			addConstraintSecondPass( ( ConstraintSecondPass) secondPass, onTopOfTheQueue );
		}
		else if ( secondPass instanceof ImplicitColumnNamingSecondPass ) {
			addImplicitColumnNamingSecondPass( (ImplicitColumnNamingSecondPass) secondPass );
		}
		else {
			// add to the general SecondPass list
			if ( general_secondPassList == null ) {
				general_secondPassList = new ArrayList<SecondPass>();
			}
			addSecondPass( secondPass, general_secondPassList, onTopOfTheQueue );
		}
	}

	private void addPkDrivenByDefaultMapsIdSecondPass(
			PkDrivenByDefaultMapsIdSecondPass secondPass,
			boolean onTopOfTheQueue) {
		if ( pkDrivenByDefaultMapsId_secondPassList == null ) {
			pkDrivenByDefaultMapsId_secondPassList = new ArrayList<PkDrivenByDefaultMapsIdSecondPass>();
		}
		addSecondPass( secondPass, pkDrivenByDefaultMapsId_secondPassList, onTopOfTheQueue );
	}

	private <T extends SecondPass> void addSecondPass(T secondPass, ArrayList<T> secondPassList, boolean onTopOfTheQueue) {
		if ( onTopOfTheQueue ) {
			secondPassList.add( 0, secondPass );
		}
		else {
			secondPassList.add( secondPass );
		}
	}

	private void addSetSimpleValueTypeSecondPass(SetSimpleValueTypeSecondPass secondPass, boolean onTopOfTheQueue) {
		if ( setSimpleValueType_secondPassList == null ) {
			setSimpleValueType_secondPassList = new ArrayList<SetSimpleValueTypeSecondPass>();
		}
		addSecondPass( secondPass, setSimpleValueType_secondPassList, onTopOfTheQueue );
	}

	private void addCopyIdentifierComponentSecondPass(
			CopyIdentifierComponentSecondPass secondPass,
			boolean onTopOfTheQueue) {
		if ( copyIdentifierComponent_secondPasList == null ) {
			copyIdentifierComponent_secondPasList = new ArrayList<CopyIdentifierComponentSecondPass>();
		}
		addSecondPass( secondPass, copyIdentifierComponent_secondPasList, onTopOfTheQueue );
	}

	private void addFkSecondPass(FkSecondPass secondPass, boolean onTopOfTheQueue) {
		if ( fk_secondPassList == null ) {
			fk_secondPassList = new ArrayList<FkSecondPass>();
		}
		addSecondPass( secondPass, fk_secondPassList, onTopOfTheQueue );
	}

	private void addCreateKeySecondPass(CreateKeySecondPass secondPass, boolean onTopOfTheQueue) {
		if ( createKey_secondPasList == null ) {
			createKey_secondPasList = new ArrayList<CreateKeySecondPass>();
		}
		addSecondPass( secondPass, createKey_secondPasList, onTopOfTheQueue );
	}

	private void addSecondaryTableSecondPass(SecondaryTableSecondPass secondPass, boolean onTopOfTheQueue) {
		if ( secondaryTable_secondPassList == null ) {
			secondaryTable_secondPassList = new ArrayList<SecondaryTableSecondPass>();
		}
		addSecondPass( secondPass, secondaryTable_secondPassList, onTopOfTheQueue );
	}

	private void addQuerySecondPass(QuerySecondPass secondPass, boolean onTopOfTheQueue) {
		if ( query_secondPassList == null ) {
			query_secondPassList = new ArrayList<QuerySecondPass>();
		}
		addSecondPass( secondPass, query_secondPassList, onTopOfTheQueue );
	}

	private void addConstraintSecondPass(ConstraintSecondPass secondPass, boolean onTopOfTheQueue) {
		if ( constraint_secondPassList == null ) {
			constraint_secondPassList = new ArrayList<ConstraintSecondPass>();
		}
		addSecondPass( secondPass, constraint_secondPassList, onTopOfTheQueue );
	}

	private void addImplicitColumnNamingSecondPass(ImplicitColumnNamingSecondPass secondPass) {
		if ( implicitColumnNaming_secondPassList == null ) {
			implicitColumnNaming_secondPassList = new ArrayList<ImplicitColumnNamingSecondPass>();
		}
		implicitColumnNaming_secondPassList.add( secondPass );
	}


	private boolean inSecondPass = false;


	/**
	 * Ugh!  But we need this done before we ask Envers to produce its entities.
	 */
	public void processSecondPasses(MetadataBuildingContext buildingContext) {
		inSecondPass = true;

		try {
			processSecondPasses( implicitColumnNaming_secondPassList );

			processSecondPasses( pkDrivenByDefaultMapsId_secondPassList );
			processSecondPasses( setSimpleValueType_secondPassList );
			processSecondPasses( copyIdentifierComponent_secondPasList );

			processFkSecondPassesInOrder();

			processSecondPasses( createKey_secondPasList );
			processSecondPasses( secondaryTable_secondPassList );

			processSecondPasses( query_secondPassList );
			processSecondPasses( general_secondPassList );

			processPropertyReferences();

			secondPassCompileForeignKeys( buildingContext );

			processSecondPasses( constraint_secondPassList );
			processUniqueConstraintHolders( buildingContext );
			processJPAIndexHolders( buildingContext );

			processNaturalIdUniqueKeyBinders();

			processCachingOverrides();
		}
		finally {
			inSecondPass = false;
		}
	}

	private void processSecondPasses(ArrayList<? extends SecondPass> secondPasses) {
		if ( secondPasses == null ) {
			return;
		}

		for ( SecondPass secondPass : secondPasses ) {
			secondPass.doSecondPass( getEntityBindingMap() );
		}

		secondPasses.clear();
	}

	private void processFkSecondPassesInOrder() {
		if ( fk_secondPassList == null || fk_secondPassList.isEmpty() ) {
			return;
		}

		// split FkSecondPass instances into primary key and non primary key FKs.
		// While doing so build a map of class names to FkSecondPass instances depending on this class.
		Map<String, Set<FkSecondPass>> isADependencyOf = new HashMap<String, Set<FkSecondPass>>();
		List<FkSecondPass> endOfQueueFkSecondPasses = new ArrayList<FkSecondPass>( fk_secondPassList.size() );
		for ( FkSecondPass sp : fk_secondPassList ) {
			if ( sp.isInPrimaryKey() ) {
				final String referenceEntityName = sp.getReferencedEntityName();
				final PersistentClass classMapping = getEntityBinding( referenceEntityName );
				final String dependentTable = classMapping.getTable().getQualifiedTableName().render();
				if ( !isADependencyOf.containsKey( dependentTable ) ) {
					isADependencyOf.put( dependentTable, new HashSet<FkSecondPass>() );
				}
				isADependencyOf.get( dependentTable ).add( sp );
			}
			else {
				endOfQueueFkSecondPasses.add( sp );
			}
		}

		// using the isADependencyOf map we order the FkSecondPass recursively instances into the right order for processing
		List<FkSecondPass> orderedFkSecondPasses = new ArrayList<FkSecondPass>( fk_secondPassList.size() );
		for ( String tableName : isADependencyOf.keySet() ) {
			buildRecursiveOrderedFkSecondPasses( orderedFkSecondPasses, isADependencyOf, tableName, tableName );
		}

		// process the ordered FkSecondPasses
		for ( FkSecondPass sp : orderedFkSecondPasses ) {
			sp.doSecondPass( getEntityBindingMap() );
		}

		processEndOfQueue( endOfQueueFkSecondPasses );

		fk_secondPassList.clear();
	}

	/**
	 * Recursively builds a list of FkSecondPass instances ready to be processed in this order.
	 * Checking all dependencies recursively seems quite expensive, but the original code just relied
	 * on some sort of table name sorting which failed in certain circumstances.
	 * <p/>
	 * See <tt>ANN-722</tt> and <tt>ANN-730</tt>
	 *
	 * @param orderedFkSecondPasses The list containing the <code>FkSecondPass<code> instances ready
	 * for processing.
	 * @param isADependencyOf Our lookup data structure to determine dependencies between tables
	 * @param startTable Table name to start recursive algorithm.
	 * @param currentTable The current table name used to check for 'new' dependencies.
	 */
	private void buildRecursiveOrderedFkSecondPasses(
			List<FkSecondPass> orderedFkSecondPasses,
			Map<String, Set<FkSecondPass>> isADependencyOf,
			String startTable,
			String currentTable) {

		Set<FkSecondPass> dependencies = isADependencyOf.get( currentTable );

		// bottom out
		if ( dependencies == null || dependencies.size() == 0 ) {
			return;
		}

		for ( FkSecondPass sp : dependencies ) {
			String dependentTable = sp.getValue().getTable().getQualifiedTableName().render();
			if ( dependentTable.compareTo( startTable ) == 0 ) {
				String sb = "Foreign key circularity dependency involving the following tables: ";
				throw new AnnotationException( sb );
			}
			buildRecursiveOrderedFkSecondPasses( orderedFkSecondPasses, isADependencyOf, startTable, dependentTable );
			if ( !orderedFkSecondPasses.contains( sp ) ) {
				orderedFkSecondPasses.add( 0, sp );
			}
		}
	}

	private void processEndOfQueue(List<FkSecondPass> endOfQueueFkSecondPasses) {
		/*
		 * If a second pass raises a recoverableException, queue it for next round
		 * stop of no pass has to be processed or if the number of pass to processes
		 * does not diminish between two rounds.
		 * If some failing pass remain, raise the original exception
		 */
		boolean stopProcess = false;
		RuntimeException originalException = null;
		while ( !stopProcess ) {
			List<FkSecondPass> failingSecondPasses = new ArrayList<FkSecondPass>();
			for ( FkSecondPass pass : endOfQueueFkSecondPasses ) {
				try {
					pass.doSecondPass( getEntityBindingMap() );
				}
				catch (RecoverableException e) {
					failingSecondPasses.add( pass );
					if ( originalException == null ) {
						originalException = (RuntimeException) e.getCause();
					}
				}
			}
			stopProcess = failingSecondPasses.size() == 0 || failingSecondPasses.size() == endOfQueueFkSecondPasses.size();
			endOfQueueFkSecondPasses = failingSecondPasses;
		}
		if ( endOfQueueFkSecondPasses.size() > 0 ) {
			throw originalException;
		}
	}

	private void secondPassCompileForeignKeys(MetadataBuildingContext buildingContext) {
		int uniqueInteger = 0;
		Set<ForeignKey> done = new HashSet<ForeignKey>();
		for ( Table table : collectTableMappings() ) {
			table.setUniqueInteger( uniqueInteger++ );
			secondPassCompileForeignKeys( table, done, buildingContext );
		}
	}

	protected void secondPassCompileForeignKeys(
			final Table table,
			Set<ForeignKey> done,
			final MetadataBuildingContext buildingContext) throws MappingException {
		table.createForeignKeys();

		Iterator itr = table.getForeignKeyIterator();
		while ( itr.hasNext() ) {
			final ForeignKey fk = (ForeignKey) itr.next();
			if ( !done.contains( fk ) ) {
				done.add( fk );
				final String referencedEntityName = fk.getReferencedEntityName();
				if ( referencedEntityName == null ) {
					throw new MappingException(
							"An association from the table " +
									fk.getTable().getName() +
									" does not specify the referenced entity"
					);
				}

				log.debugf( "Resolving reference to class: %s", referencedEntityName );
				final PersistentClass referencedClass = getEntityBinding( referencedEntityName );
				if ( referencedClass == null ) {
					throw new MappingException(
							"An association from the table " +
									fk.getTable().getName() +
									" refers to an unmapped class: " +
									referencedEntityName
					);
				}
				if ( referencedClass.isJoinedSubclass() ) {
					secondPassCompileForeignKeys( referencedClass.getSuperclass().getTable(), done, buildingContext );
				}

				fk.setReferencedTable( referencedClass.getTable() );

				// todo : should we apply a physical naming too?
				if ( fk.getName() == null ) {
					final Identifier nameIdentifier = getMetadataBuildingOptions().getImplicitNamingStrategy().determineForeignKeyName(
							new ImplicitForeignKeyNameSource() {
								final List<Identifier> columnNames = extractColumnNames( fk.getColumns() );
								List<Identifier> referencedColumnNames = null;

								@Override
								public Identifier getTableName() {
									return table.getNameIdentifier();
								}

								@Override
								public List<Identifier> getColumnNames() {
									return columnNames;
								}

								@Override
								public Identifier getReferencedTableName() {
									return fk.getReferencedTable().getNameIdentifier();
								}

								@Override
								public List<Identifier> getReferencedColumnNames() {
									if ( referencedColumnNames == null ) {
										referencedColumnNames = extractColumnNames( fk.getReferencedColumns() );
									}
									return referencedColumnNames;
								}

								@Override
								public MetadataBuildingContext getBuildingContext() {
									return buildingContext;
								}
							}
					);

					fk.setName( nameIdentifier.render( getDatabase().getJdbcEnvironment().getDialect() ) );
				}

				fk.alignColumns();
			}
		}
	}

	private List<Identifier> toIdentifiers(List<String> names) {
		if ( names == null || names.isEmpty() ) {
			return Collections.emptyList();
		}

		final List<Identifier> columnNames = CollectionHelper.arrayList( names.size() );
		for ( String name : names ) {
			columnNames.add( getDatabase().toIdentifier( name ) );
		}
		return columnNames;
	}

	private List<Identifier> toIdentifiers(String[] names) {
		if ( names == null ) {
			return Collections.emptyList();
		}

		final List<Identifier> columnNames = CollectionHelper.arrayList( names.length );
		for ( String name : names ) {
			columnNames.add( getDatabase().toIdentifier( name ) );
		}
		return columnNames;
	}

	@SuppressWarnings("unchecked")
	private List<Identifier> extractColumnNames(List columns) {
		if ( columns == null || columns.isEmpty() ) {
			return Collections.emptyList();
		}

		final List<Identifier> columnNames = CollectionHelper.arrayList( columns.size() );
		for ( Column column : (List<Column>) columns ) {
			columnNames.add( getDatabase().toIdentifier( column.getQuotedName() ) );
		}
		return columnNames;

	}

	private void processPropertyReferences() {
		if ( delayedPropertyReferenceHandlers == null ) {
			return;
		}
		log.debug( "Processing association property references" );

		for ( DelayedPropertyReferenceHandler delayedPropertyReferenceHandler : delayedPropertyReferenceHandlers ) {
			delayedPropertyReferenceHandler.process( this );
		}

		delayedPropertyReferenceHandlers.clear();
	}

	private void processUniqueConstraintHolders(MetadataBuildingContext buildingContext) {
		if ( uniqueConstraintHoldersByTable == null ) {
			return;
		}

		for ( Map.Entry<Table, List<UniqueConstraintHolder>> tableListEntry : uniqueConstraintHoldersByTable.entrySet() ) {
			final Table table = tableListEntry.getKey();
			final List<UniqueConstraintHolder> uniqueConstraints = tableListEntry.getValue();
			for ( UniqueConstraintHolder holder : uniqueConstraints ) {
				buildUniqueKeyFromColumnNames( table, holder.getName(), holder.getColumns(), buildingContext );
			}
		}

		uniqueConstraintHoldersByTable.clear();
	}

	private void buildUniqueKeyFromColumnNames(
			Table table,
			String keyName,
			String[] columnNames,
			MetadataBuildingContext buildingContext) {
		buildUniqueKeyFromColumnNames( table, keyName, columnNames, null, true, buildingContext );
	}

	private void buildUniqueKeyFromColumnNames(
			final Table table,
			String keyName,
			final String[] columnNames,
			String[] orderings,
			boolean unique,
			final MetadataBuildingContext buildingContext) {
		int size = columnNames.length;
		Column[] columns = new Column[size];
		Set<Column> unbound = new HashSet<Column>();
		Set<Column> unboundNoLogical = new HashSet<Column>();
		for ( int index = 0; index < size; index++ ) {
			final String logicalColumnName = columnNames[index];
			try {
				final String physicalColumnName = getPhysicalColumnName( table, logicalColumnName );
				columns[index] = new Column( physicalColumnName );
				unbound.add( columns[index] );
				//column equals and hashcode is based on column name
			}
			catch ( MappingException e ) {
				// If at least 1 columnName does exist, 'columns' will contain a mix of Columns and nulls.  In order
				// to exhaustively report all of the unbound columns at once, w/o an NPE in
				// Constraint#generateName's array sorting, simply create a fake Column.
				columns[index] = new Column( logicalColumnName );
				unboundNoLogical.add( columns[index] );
			}
		}

		if ( unique ) {
			if ( StringHelper.isEmpty( keyName ) ) {
				final Identifier keyNameIdentifier = getMetadataBuildingOptions().getImplicitNamingStrategy().determineUniqueKeyName(
						new ImplicitUniqueKeyNameSource() {
							@Override
							public MetadataBuildingContext getBuildingContext() {
								return buildingContext;
							}

							@Override
							public Identifier getTableName() {
								return table.getNameIdentifier();
							}

							private List<Identifier> columnNameIdentifiers;

							@Override
							public List<Identifier> getColumnNames() {
								// be lazy about building these
								if ( columnNameIdentifiers == null ) {
									columnNameIdentifiers = toIdentifiers( columnNames );
								}
								return columnNameIdentifiers;
							}
						}
				);
				keyName = keyNameIdentifier.render( getDatabase().getJdbcEnvironment().getDialect() );
			}

			UniqueKey uk = table.getOrCreateUniqueKey( keyName );
			for ( int i = 0; i < columns.length; i++ ) {
				Column column = columns[i];
				String order = orderings != null ? orderings[i] : null;
				if ( table.containsColumn( column ) ) {
					uk.addColumn( column, order );
					unbound.remove( column );
				}
			}
		}
		else {
			if ( StringHelper.isEmpty( keyName ) ) {
				final Identifier keyNameIdentifier = getMetadataBuildingOptions().getImplicitNamingStrategy().determineIndexName(
						new ImplicitIndexNameSource() {
							@Override
							public MetadataBuildingContext getBuildingContext() {
								return buildingContext;
							}

							@Override
							public Identifier getTableName() {
								return table.getNameIdentifier();
							}

							private List<Identifier> columnNameIdentifiers;

							@Override
							public List<Identifier> getColumnNames() {
								// be lazy about building these
								if ( columnNameIdentifiers == null ) {
									columnNameIdentifiers = toIdentifiers( columnNames );
								}
								return columnNameIdentifiers;
							}
						}
				);
				keyName = keyNameIdentifier.render( getDatabase().getJdbcEnvironment().getDialect() );
			}

			Index index = table.getOrCreateIndex( keyName );
			for ( int i = 0; i < columns.length; i++ ) {
				Column column = columns[i];
				String order = orderings != null ? orderings[i] : null;
				if ( table.containsColumn( column ) ) {
					index.addColumn( column, order );
					unbound.remove( column );
				}
			}
		}

		if ( unbound.size() > 0 || unboundNoLogical.size() > 0 ) {
			StringBuilder sb = new StringBuilder( "Unable to create unique key constraint (" );
			for ( String columnName : columnNames ) {
				sb.append( columnName ).append( ", " );
			}
			sb.setLength( sb.length() - 2 );
			sb.append( ") on table " ).append( table.getName() ).append( ": database column " );
			for ( Column column : unbound ) {
				sb.append("'").append( column.getName() ).append( "', " );
			}
			for ( Column column : unboundNoLogical ) {
				sb.append("'").append( column.getName() ).append( "', " );
			}
			sb.setLength( sb.length() - 2 );
			sb.append( " not found. Make sure that you use the correct column name which depends on the naming strategy in use (it may not be the same as the property name in the entity, especially for relational types)" );
			throw new AnnotationException( sb.toString() );
		}
	}

	private void processJPAIndexHolders(MetadataBuildingContext buildingContext) {
		if ( jpaIndexHoldersByTable == null ) {
			return;
		}

		for ( Table table : jpaIndexHoldersByTable.keySet() ) {
			final List<JPAIndexHolder> jpaIndexHolders = jpaIndexHoldersByTable.get( table );
			for ( JPAIndexHolder holder : jpaIndexHolders ) {
				buildUniqueKeyFromColumnNames(
						table,
						holder.getName(),
						holder.getColumns(),
						holder.getOrdering(),
						holder.isUnique(),
						buildingContext
				);
			}
		}
	}

	private Map<String,NaturalIdUniqueKeyBinder> naturalIdUniqueKeyBinderMap;

	@Override
	public NaturalIdUniqueKeyBinder locateNaturalIdUniqueKeyBinder(String entityName) {
		if ( naturalIdUniqueKeyBinderMap == null ) {
			return null;
		}
		return naturalIdUniqueKeyBinderMap.get( entityName );
	}

	@Override
	public void registerNaturalIdUniqueKeyBinder(String entityName, NaturalIdUniqueKeyBinder ukBinder) {
		if ( naturalIdUniqueKeyBinderMap == null ) {
			naturalIdUniqueKeyBinderMap = new HashMap<String, NaturalIdUniqueKeyBinder>();
		}
		final NaturalIdUniqueKeyBinder previous = naturalIdUniqueKeyBinderMap.put( entityName, ukBinder );
		if ( previous != null ) {
			throw new AssertionFailure( "Previous NaturalIdUniqueKeyBinder already registered for entity name : " + entityName );
		}
	}

	private void processNaturalIdUniqueKeyBinders() {
		if ( naturalIdUniqueKeyBinderMap == null ) {
			return;
		}

		for ( NaturalIdUniqueKeyBinder naturalIdUniqueKeyBinder : naturalIdUniqueKeyBinderMap.values() ) {
			naturalIdUniqueKeyBinder.process();
		}

		naturalIdUniqueKeyBinderMap.clear();
	}

	private void processCachingOverrides() {
		if ( options.getCacheRegionDefinitions() == null ) {
			return;
		}

		for ( CacheRegionDefinition cacheRegionDefinition : options.getCacheRegionDefinitions() ) {
			if ( cacheRegionDefinition.getRegionType() == CacheRegionDefinition.CacheRegionType.ENTITY ) {
				final PersistentClass entityBinding = getEntityBinding( cacheRegionDefinition.getRole() );
				if ( entityBinding == null ) {
					throw new HibernateException(
							"Cache override referenced an unknown entity : " + cacheRegionDefinition.getRole()
					);
				}
				if ( !RootClass.class.isInstance( entityBinding ) ) {
					throw new HibernateException(
							"Cache override referenced a non-root entity : " + cacheRegionDefinition.getRole()
					);
				}
				( (RootClass) entityBinding ).setCacheRegionName( cacheRegionDefinition.getRegion() );
				( (RootClass) entityBinding ).setCacheConcurrencyStrategy( cacheRegionDefinition.getUsage() );
				( (RootClass) entityBinding ).setLazyPropertiesCacheable( cacheRegionDefinition.isCacheLazy() );
			}
			else if ( cacheRegionDefinition.getRegionType() == CacheRegionDefinition.CacheRegionType.COLLECTION ) {
				final Collection collectionBinding = getCollectionBinding( cacheRegionDefinition.getRole() );
				if ( collectionBinding == null ) {
					throw new HibernateException(
							"Cache override referenced an unknown collection role : " + cacheRegionDefinition.getRole()
					);
				}
				collectionBinding.setCacheRegionName( cacheRegionDefinition.getRegion() );
				collectionBinding.setCacheConcurrencyStrategy( cacheRegionDefinition.getUsage() );
			}
		}
	}

	@Override
	public boolean isInSecondPass() {
		return inSecondPass;
	}

	/**
	 * Builds the complete and immutable Metadata instance from the collected info.
	 *
	 * @return The complete and immutable Metadata instance
	 */
	public MetadataImpl buildMetadataInstance(MetadataBuildingContext buildingContext) {
		processSecondPasses( buildingContext );
		processExportableProducers( buildingContext );

		return new MetadataImpl(
				uuid,
				options,
				typeResolver,
				identifierGeneratorFactory,
				entityBindingMap,
				mappedSuperClasses,
				collectionBindingMap,
				typeDefinitionMap,
				filterDefinitionMap,
				fetchProfileMap,
				imports,
				idGeneratorDefinitionMap,
				namedQueryMap,
				namedNativeQueryMap,
				namedProcedureCallMap,
				sqlResultSetMappingMap,
				namedEntityGraphMap,
				sqlFunctionMap,
				getDatabase()
		);
	}

	private void processExportableProducers(MetadataBuildingContext buildingContext) {
		// for now we only handle id generators as ExportableProducers

		final Dialect dialect = getDatabase().getJdbcEnvironment().getDialect();
		final String defaultCatalog = extractName( getDatabase().getDefaultSchema().getName().getCatalog(), dialect );
		final String defaultSchema = extractName( getDatabase().getDefaultSchema().getName().getSchema(), dialect );

		for ( PersistentClass entityBinding : entityBindingMap.values() ) {
			if ( entityBinding.isInherited() ) {
				continue;
			}

			handleIdentifierValueBinding(
					entityBinding.getIdentifier(),
					dialect,
					defaultCatalog,
					defaultSchema,
					(RootClass) entityBinding
			);
		}

		for ( Collection collection : collectionBindingMap.values() ) {
			if ( !IdentifierCollection.class.isInstance( collection ) ) {
				continue;
			}

			handleIdentifierValueBinding(
					( (IdentifierCollection) collection ).getIdentifier(),
					dialect,
					defaultCatalog,
					defaultSchema,
					null
			);
		}
	}

	private void handleIdentifierValueBinding(
			KeyValue identifierValueBinding,
			Dialect dialect,
			String defaultCatalog,
			String defaultSchema,
			RootClass entityBinding) {
		// todo : store this result (back into the entity or into the KeyValue, maybe?)
		// 		This process of instantiating the id-generator is called multiple times.
		//		It was done this way in the old code too, so no "regression" here; but
		//		it could be done better
		try {
			final IdentifierGenerator ig = identifierValueBinding.createIdentifierGenerator(
					getIdentifierGeneratorFactory(),
					dialect,
					defaultCatalog,
					defaultSchema,
					entityBinding
			);

			if ( ig instanceof ExportableProducer ) {
				( (ExportableProducer) ig ).registerExportables( getDatabase() );
			}
		}
		catch (MappingException e) {
			// ignore this for now.  The reasoning being "non-reflective" binding as needed
			// by tools.  We want to hold off requiring classes being present until we
			// try to build a SF.  Here, just building the Metadata, it is "ok" for an
			// exception to occur, the same exception will happen later as we build the SF.
			log.debugf( "Ignoring exception thrown when trying to build IdentifierGenerator as part of Metadata building", e );
		}
	}

	private String extractName(Identifier identifier, Dialect dialect) {
		if ( identifier == null ) {
			return null;
		}
		return identifier.render( dialect );
	}
}