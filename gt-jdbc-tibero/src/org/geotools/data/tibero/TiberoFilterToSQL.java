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
package org.geotools.data.tibero;

import java.io.IOException;

import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.filter.FilterCapabilities;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.spatial.BinarySpatialOperator;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LinearRing;

@SuppressWarnings("deprecation")
public class TiberoFilterToSQL extends FilterToSQL {

    FilterToSqlHelper helper;

    public TiberoFilterToSQL(TiberoDialect dialect) {
        helper = new FilterToSqlHelper(this);
    }

    public boolean isLooseBBOXEnabled() {
        return helper.looseBBOXEnabled;
    }

    public void setLooseBBOXEnabled(boolean looseBBOXEnabled) {
        helper.looseBBOXEnabled = looseBBOXEnabled;
    }

    @Override
    protected void visitLiteralGeometry(Literal expression) throws IOException {
        // evaluate the literal and store it for later
        Geometry geom = (Geometry) evaluateLiteral(expression, Geometry.class);

        if (geom instanceof LinearRing) {
            // WKT does not support linear rings
            geom = geom.getFactory().createLineString(((LinearRing) geom).getCoordinateSequence());
        }

        out.write("ST_GEOMFROMTEXT('");
        out.write(geom.toText());
        out.write("')");
    }

    @Override
    @SuppressWarnings({ "static-access" })
    protected FilterCapabilities createFilterCapabilities() {
        return helper.createFilterCapabilities();
    }

    protected Object visitBinarySpatialOperator(BinarySpatialOperator filter,
            PropertyName property, Literal geometry, boolean swapped, Object extraData) {
        helper.out = out;
        return helper.visitBinarySpatialOperator(filter, property, geometry, swapped, extraData);
    }

}
