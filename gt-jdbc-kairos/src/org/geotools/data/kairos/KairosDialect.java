/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.kairos;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.jdbc.ColumnMetadata;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.jdbc.PreparedStatementSQLDialect;
import org.geotools.referencing.CRS;
import org.geotools.util.Version;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.io.WKBWriter;
import com.vividsolutions.jts.io.WKTReader;

public class KairosDialect extends PreparedStatementSQLDialect {
    // http://docs.geotools.org/latest/javadocs/org/geotools/data/postgis/PostGISDialect.html

    static final Version V_5_0_0 = new Version("5.0.0");

    boolean looseBBOXEnabled = false;

    boolean estimatedExtentsEnabled = false;

    Version version;

    WKBWriter wkbWriter = new WKBWriter();

    static Integer GEOM_POINT = Integer.valueOf(4000);

    static Integer GEOM_LINESTRING = Integer.valueOf(4001);

    static Integer GEOM_POLYGON = Integer.valueOf(4002);

    static Integer GEOM_MULTIPOINT = Integer.valueOf(4003);

    static Integer GEOM_MULTILINESTRING = Integer.valueOf(4004);

    static Integer GEOM_MULTIPOLYGON = Integer.valueOf(4005);

    static Integer GEOM_GEOMCOLLECTION = Integer.valueOf(4006);

    @SuppressWarnings({ "rawtypes", "serial" })
    final static Map<String, Class> TYPE_TO_CLASS_MAP = new HashMap<String, Class>() {
        {
            put("GEOMETRY", Geometry.class);
            put("POINT", Point.class);
            put("POINTM", Point.class);
            put("POINTZ", Point.class);
            put("LINESTRING", LineString.class);
            put("LINESTRINGM", LineString.class);
            put("LINESTRINGZ", LineString.class);
            put("POLYGON", Polygon.class);
            put("POLYGONM", Polygon.class);
            put("POLYGONZ", Polygon.class);
            put("MULTIPOINT", MultiPoint.class);
            put("MULTIPOINTM", MultiPoint.class);
            put("MULTIPOINTZ", MultiPoint.class);
            put("MULTILINESTRING", MultiLineString.class);
            put("MULTILINESTRINGM", MultiLineString.class);
            put("MULTILINESTRINGZ", MultiLineString.class);
            put("MULTIPOLYGON", MultiPolygon.class);
            put("MULTIPOLYGONM", MultiPolygon.class);
            put("MULTIPOLYGONZ", MultiPolygon.class);
            put("GEOMETRYCOLLECTION", GeometryCollection.class);
            put("GEOMETRYCOLLECTIONM", GeometryCollection.class);
            put("GEOMETRYCOLLECTIONZ", GeometryCollection.class);
            put("BYTEA", byte[].class);
        }
    };

    @SuppressWarnings({ "rawtypes", "serial" })
    final static Map<Class, String> CLASS_TO_TYPE_MAP = new HashMap<Class, String>() {
        {
            put(Geometry.class, "GEOMETRY");
            put(Point.class, "POINT");
            put(LineString.class, "LINESTRING");
            put(Polygon.class, "POLYGON");
            put(MultiPoint.class, "MULTIPOINT");
            put(MultiLineString.class, "MULTILINESTRING");
            put(MultiPolygon.class, "MULTIPOLYGON");
            put(GeometryCollection.class, "GEOMCOLLECTION");
            put(byte[].class, "BYTEA");
        }
    };

    public KairosDialect(JDBCDataStore dataStore) {
        super(dataStore);
    }

    public boolean isLooseBBOXEnabled() {
        return looseBBOXEnabled;
    }

    public void setLooseBBOXEnabled(boolean looseBBOXEnabled) {
        this.looseBBOXEnabled = looseBBOXEnabled;
    }

    public boolean isEstimatedExtentsEnabled() {
        return estimatedExtentsEnabled;
    }

    public void setEstimatedExtentsEnabled(boolean estimatedExtentsEnabled) {
        this.estimatedExtentsEnabled = estimatedExtentsEnabled;
    }

    @Override
    public boolean includeTable(String schemaName, String tableName, Connection cx)
            throws SQLException {
        if (tableName.equalsIgnoreCase("GEOMETRY_COLUMNS")) {
            return false;
        } else if (tableName.equalsIgnoreCase("SPATIAL_REF_SYS")) {
            return false;
        } else if (tableName.equalsIgnoreCase("sys_plan_view")) {
            return false;
        }

        // others?
        return true;
    }

    @Override
    public Geometry decodeGeometryValue(GeometryDescriptor descriptor, ResultSet rs, String column,
            GeometryFactory factory, Connection cx) throws IOException, SQLException {

        byte[] bytes = rs.getBytes(column);
        if (bytes == null) {
            return null;
        }
        try {
            return new WKBReader(factory).read(bytes);
        } catch (ParseException e) {
            String msg = "Error decoding wkb";
            throw (IOException) new IOException(msg).initCause(e);
        }
    }

    public Geometry decodeGeometryValue(GeometryDescriptor descriptor, ResultSet rs, int column,
            GeometryFactory factory, Connection cx) throws IOException, SQLException {
        byte[] bytes = rs.getBytes(column);
        if (bytes == null) {
            return null;
        }

        try {
            return new WKBReader(factory).read(bytes);
        } catch (ParseException e) {
            String msg = "Error decoding wkb";
            throw (IOException) new IOException(msg).initCause(e);
        }
    }

    @Override
    public void encodeGeometryColumn(GeometryDescriptor gatt, int srid, StringBuffer sql) {
        // TODO: GeoTools 8.x에서 final method로 변경 
        sql.append(" ST_ASBINARY(");
        encodeColumnName(gatt.getLocalName(), sql);
        sql.append(")");
    }

    @Override
    public void encodeGeometryEnvelope(String tableName, String geometryColumn, StringBuffer sql) {
        sql.append(" ST_ASTEXT(ST_ENVELOPE(");
        encodeColumnName(geometryColumn, sql);
        sql.append("))");
    }

    @Override
    public Envelope decodeGeometryEnvelope(ResultSet rs, int column, Connection cx)
            throws SQLException, IOException {
        try {
            String envelope = rs.getString(column);
            if (envelope != null) {
                return new WKTReader().read(envelope).getEnvelopeInternal();
            } else {
                return new Envelope();
            }
        } catch (ParseException e) {
            throw (IOException) new IOException("Error occurred parsing the bounds WKT")
                    .initCause(e);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void setGeometryValue(Geometry g, int srid, Class binding, PreparedStatement ps,
            int column) throws SQLException {
        if (g != null) {
            if (g instanceof LinearRing) {
                // WKT does not support linear rings
                g = g.getFactory().createLineString(((LinearRing) g).getCoordinateSequence());
            }

            byte[] bytes = new WKBWriter().write(g);
            ps.setBytes(column, bytes);
        } else {
            ps.setNull(column, Types.OTHER, "Geometry");
        }
    }

    @Override
    public List<ReferencedEnvelope> getOptimizedBounds(String schema,
            SimpleFeatureType featureType, Connection cx) throws SQLException, IOException {
        if (!estimatedExtentsEnabled)
            return null;

        String tableName = featureType.getTypeName();

        Statement st = null;
        ResultSet rs = null;

        List<ReferencedEnvelope> result = new ArrayList<ReferencedEnvelope>();
        Savepoint savePoint = null;
        try {
            st = cx.createStatement();
            if (!cx.getAutoCommit()) {
                savePoint = cx.setSavepoint();
            }

            GeometryDescriptor att = featureType.getGeometryDescriptor();
            String geometryField = att.getName().getLocalPart();

            // ==================Kairos======================
            // SELECT ST_ASBINARY(ST_EXTENT(geom)) from fishnet
            // ================================================

            // use estimated extent (optimizer statistics)
            StringBuffer sql = new StringBuffer();
            sql.append("SELECT ST_ASBINARY(ST_EXTENT(\"");
            sql.append(geometryField).append("\"))");
            sql.append(" FROM \"");
            sql.append(tableName);
            sql.append("\"");

            rs = st.executeQuery(sql.toString());

            if (rs.next()) {
                byte[] bytes = rs.getBytes(1);
                if (bytes != null) {
                    try {
                        Geometry extGeom = new WKBReader().read(bytes);
                        CoordinateReferenceSystem crs = att.getCoordinateReferenceSystem();

                        // reproject and merge
                        result.add(new ReferencedEnvelope(extGeom.getEnvelopeInternal(), crs));
                    } catch (ParseException e) {
                        String msg = "Error decoding wkb";
                        throw (IOException) new IOException(msg).initCause(e);
                    }
                }
            }
        } catch (SQLException e) {
            if (savePoint != null) {
                cx.rollback(savePoint);
            }
            LOGGER.log(Level.WARNING,
                    "Failed to use ST_Estimated_Extent, falling back on envelope aggregation", e);
            return null;
        } finally {
            if (savePoint != null) {
                cx.releaseSavepoint(savePoint);
            }
            dataStore.closeSafe(rs);
            dataStore.closeSafe(st);
        }
        return result;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class<?> getMapping(ResultSet columnMetaData, Connection cx) throws SQLException {
        String typeName = columnMetaData.getString("TYPE_NAME");

        Class<?> geometryClass = (Class) TYPE_TO_CLASS_MAP.get(typeName.toUpperCase());
        return geometryClass;
    }

    @Override
    public void handleUserDefinedType(ResultSet columnMetaData, ColumnMetadata metadata,
            Connection cx) throws SQLException {
        String tableName = columnMetaData.getString("TABLE_NAME");
        String columnName = columnMetaData.getString("COLUMN_NAME");
        String schemaName = columnMetaData.getString("TABLE_SCHEM");

        String sql = "SELECT udt_name FROM information_schema.columns " + " WHERE table_schema = '"
                + schemaName + "' " + " AND table_name = '" + tableName + "' "
                + " AND column_name = '" + columnName + "' ";
        LOGGER.fine(sql);

        Statement st = cx.createStatement();
        try {
            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    metadata.setTypeName(rs.getString(1));
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }
    }

    @Override
    public Integer getGeometrySRID(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {
        // first attempt, try with the geometry metadata
        Statement statement = null;
        ResultSet result = null;
        Integer srid = null;
        try {
            if (schemaName == null || schemaName.equalsIgnoreCase("public")) {
                schemaName = "";
            }

            // try geometry_columns
            try {
                String sqlStatement = "SELECT SRID FROM GEOMETRY_COLUMNS WHERE " //
                        + "F_TABLE_SCHEMA = '" + schemaName + "' " //
                        + "AND F_TABLE_NAME = '" + tableName + "' " //
                        + "AND F_GEOMETRY_COLUMN = '" + columnName + "'";

                LOGGER.log(Level.FINE, "Geometry srid check; {0} ", sqlStatement);
                statement = cx.createStatement();
                result = statement.executeQuery(sqlStatement);

                if (result.next()) {
                    srid = result.getInt(1);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to retrieve information about " + schemaName
                        + "." + tableName + "." + columnName
                        + " from the geometry_columns table, checking the first geometry instead",
                        e);
            } finally {
                dataStore.closeSafe(result);
            }

        } finally {
            dataStore.closeSafe(result);
            dataStore.closeSafe(statement);
        }

        return srid;
    }

    @Override
    public String getSequenceForColumn(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {

        if (columnName.toUpperCase().contains("GEOM") || columnName.toUpperCase().contains("SHAPE")) {
            return null;
        }

        Statement st = cx.createStatement();
        try {
            String seqName = "seq_" + tableName + "_" + columnName;

            String sql = "SELECT seqname from syssequence WHERE seqname = '";
            sql += seqName + "'";

            dataStore.getLogger().fine(sql);
            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    return rs.getString(1);
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }

        return null;
    }

    @Override
    public Object getNextSequenceValue(String schemaName, String sequenceName, Connection cx)
            throws SQLException {
        Statement st = cx.createStatement();
        try {
            // SELECT seq_building_fid.NEXTVAL FROM DUAL;
            String sql = "SELECT " + sequenceName + ".NEXTVAL FROM DUAL";

            dataStore.getLogger().fine(sql);
            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    LOGGER.log(Level.WARNING, "Failed to retrieve sequence from " + sequenceName);
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }

        return 0;
    }

    @Override
    public boolean lookupGeneratedValuesPostInsert() {
        return true;
    }

    @Override
    public void registerClassToSqlMappings(Map<Class<?>, Integer> mappings) {
        super.registerClassToSqlMappings(mappings);

        // jdbc metadata for geom columns reports DATA_TYPE=1111=Types.OTHER
        mappings.put(Geometry.class, Types.OTHER);

        // Geometry Type for Kairos
        mappings.put(Point.class, GEOM_POINT);
        mappings.put(LineString.class, GEOM_LINESTRING);
        mappings.put(Polygon.class, GEOM_POLYGON);
        mappings.put(MultiPoint.class, GEOM_MULTIPOINT);
        mappings.put(MultiLineString.class, GEOM_MULTILINESTRING);
        mappings.put(MultiPolygon.class, GEOM_MULTIPOLYGON);
        mappings.put(GeometryCollection.class, GEOM_GEOMCOLLECTION);
    }

    @Override
    public void registerSqlTypeNameToClassMappings(Map<String, Class<?>> mappings) {
        super.registerSqlTypeNameToClassMappings(mappings);

        mappings.put("GEOMETRY", Geometry.class);
        mappings.put("TEXT", String.class);
        mappings.put("POINT", Point.class);
        mappings.put("POINTM", Point.class);
        mappings.put("POINTZ", Point.class);
        mappings.put("LINESTRING", LineString.class);
        mappings.put("LINESTRINGM", LineString.class);
        mappings.put("LINESTRINGZ", LineString.class);
        mappings.put("POLYGON", Polygon.class);
        mappings.put("POLYGONM", Polygon.class);
        mappings.put("POLYGONZ", Polygon.class);
        mappings.put("MULTIPOINT", MultiPoint.class);
        mappings.put("MULTIPOINTM", MultiPoint.class);
        mappings.put("MULTIPOINTZ", MultiPoint.class);
        mappings.put("MULTILINESTRING", MultiLineString.class);
        mappings.put("MULTILINESTRINGM", MultiLineString.class);
        mappings.put("MULTILINESTRINGZ", MultiLineString.class);
        mappings.put("MULTIPOLYGON", MultiPolygon.class);
        mappings.put("MULTIPOLYGONM", MultiPolygon.class);
        mappings.put("MULTIPOLYGONZ", MultiPolygon.class);
        mappings.put("GEOMETRYCOLLECTION", GeometryCollection.class);
        mappings.put("GEOMETRYCOLLECTIONM", GeometryCollection.class);
        mappings.put("GEOMETRYCOLLECTIONZ", GeometryCollection.class);
        mappings.put("BYTEA", byte[].class);
    }

    @Override
    public void registerSqlTypeToSqlTypeNameOverrides(Map<Integer, String> overrides) {
        overrides.put(new Integer(Types.VARCHAR), "VARCHAR");
        overrides.put(new Integer(Types.BOOLEAN), "BOOL");
        overrides.put(new Integer(Types.SMALLINT), "INTEGER");
        overrides.put(new Integer(Types.INTEGER), "INTEGER");
        overrides.put(new Integer(Types.REAL), "REAL");
        overrides.put(new Integer(Types.FLOAT), "DOUBLE");
        overrides.put(new Integer(Types.DOUBLE), "DOUBLE");
        overrides.put(new Integer(Types.DECIMAL), "NUMBER");
        overrides.put(new Integer(Types.NUMERIC), "NUMBER");
    }

    @Override
    public String getGeometryTypeName(Integer type) {
        switch (type) {
        case 4000:
            return "ST_POINT";
        case 4001:
            return "ST_MULTILINESTRING";
        case 4002:
            return "ST_MULTIPOLYGON";
        case 4003:
            return "ST_MULTIPOINT";
        case 4004:
            return "ST_MULTILINESTRING";
        case 4005:
            return "ST_MULTIPOLYGON";
        case 4006:
            return "ST_GEOMCOLLECTION";
        }

        return "ST_GEOMETRY";
    }

    @Override
    public void encodePrimaryKey(String column, StringBuffer sql) {
        encodeColumnName(column, sql);
        sql.append(" INTEGER PRIMARY KEY");
    }

    /**
     * Creates GEOMETRY_COLUMN registrations and spatial indexes for all geometry columns
     */
    @Override
    public void postCreateTable(String schemaName, SimpleFeatureType featureType, Connection cx)
            throws SQLException {
        schemaName = schemaName != null ? schemaName : "";
        String tableName = featureType.getName().getLocalPart();

        Statement st = null;
        try {
            st = cx.createStatement();

            // register all geometry columns in the database
            for (AttributeDescriptor att : featureType.getAttributeDescriptors()) {
                if (att instanceof GeometryDescriptor) {
                    GeometryDescriptor gd = (GeometryDescriptor) att;

                    // lookup or reverse engineer the srid
                    int srid = -1;

                    if (gd.getUserData().get(JDBCDataStore.JDBC_NATIVE_SRID) != null) {
                        srid = (Integer) gd.getUserData().get(JDBCDataStore.JDBC_NATIVE_SRID);
                    } else if (gd.getCoordinateReferenceSystem() != null) {
                        try {
                            Integer result = CRS.lookupEpsgCode(gd.getCoordinateReferenceSystem(),
                                    true);
                            if (result != null) {
                                srid = result;
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Error looking up the "
                                    + "epsg code for metadata " + "insertion, assuming -1", e);
                        }
                    }

                    // assume 2 dimensions, but ease future customisation
                    int dimensions = 2;

                    // grab the geometry type
                    String geomType = CLASS_TO_TYPE_MAP.get(gd.getType().getBinding());
                    if (geomType == null) {
                        geomType = "GEOMETRY";
                    }

                    // register the geometry type, first remove and eventual
                    // leftover, then write out the real one
                    String sql = "DELETE FROM GEOMETRY_COLUMNS" + " WHERE f_table_catalog =''" //
                            + " AND f_table_schema = '" + schemaName + "'" //
                            + " AND F_TABLE_NAME = '" + tableName + "'" //
                            + " AND F_GEOMETRY_COLUMN = '" + gd.getLocalName() + "'";

                    LOGGER.fine(sql);
                    st.execute(sql);

                    sql = "INSERT INTO GEOMETRY_COLUMNS VALUES (''," //
                            + "'" + schemaName + "'," //
                            + "'" + tableName + "'," //
                            + "'" + gd.getLocalName() + "'," //
                            + dimensions + "," //
                            + srid + "," //
                            + "'" + geomType + "')";
                    LOGGER.fine(sql);
                    st.execute(sql);

                    // add the spatial index
                    // Kairos: CREATE [UNIQUE] [RSTREE] INDEX IndexName ON TableName (GeoColName)
                    // ex) CREATE RSTREE INDEX idx_fishnet_geom ON fishnet(geom);
                    sql = "CREATE RSTREE INDEX \"spatial_" + tableName //
                            + "_" + gd.getLocalName().toLowerCase() + "\"" //
                            + " ON " //
                            + "\"" + tableName + "\"" //
                            + " (" //
                            + "\"" + gd.getLocalName() + "\")";
                    LOGGER.fine(sql);
                    st.execute(sql);

                    // create sequence
                    // CREATE SEQUENCE seq_building_fid START WITH 1 INCREMENT BY 1 MINVALUE 1
                    // NOMAXVALUE
                    sql = "CREATE SEQUENCE seq_" + tableName //
                            + "_fid START WITH 1 INCREMENT BY 1 MINVALUE 1 NOMAXVALUE";
                    LOGGER.fine(sql);
                    st.execute(sql);
                }
            }
            cx.commit();
        } finally {
            dataStore.closeSafe(st);
        }
    }

    @Override
    public boolean isLimitOffsetSupported() {
        return true;
    }

    @Override
    public void applyLimitOffset(StringBuffer sql, int limit, int offset) {
        if (limit >= 0 && limit < Integer.MAX_VALUE) {
            sql.append(" LIMIT " + limit);
            if (offset > 0) {
                sql.append(" OFFSET " + offset);
            }
        } else if (offset > 0) {
            sql.append(" OFFSET " + offset);
        }
    }

    @Override
    public int getDefaultVarcharSize() {
        return 255;
    }

    /**
     * Returns the Kairos version
     * 
     * @return
     */
    public Version getVersion(Connection conn) throws SQLException {
        if (version == null) {
            version = new Version("V_5_0_0");
        }
        return version;
    }

    /**
     * Returns true if the Kairos version is >= x.x
     */
    boolean supportsGeography(Connection cx) throws SQLException {
        return getVersion(cx).compareTo(V_5_0_0) >= 0;
    }

}
