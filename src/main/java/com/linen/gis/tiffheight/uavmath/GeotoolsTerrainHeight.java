package com.linen.gis.tiffheight.uavmath;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.geometry.DirectPosition2D;
import org.locationtech.jts.geom.Coordinate;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import static com.linen.gis.tiffheight.config.HeightConfiguration.*;


/**
 * @Author yanll
 * @Date: 2020/7/27 13:45
 * @Description:
 **/
public class GeotoolsTerrainHeight implements TerrainProvider {
    private GridCoverage2D coverage2D = GRID_COVERAGE_2D;

    @Override
    public double getHeight(double lon, double lat) {
        CoordinateReferenceSystem crs = coverage2D.getCoordinateReferenceSystem2D();
        DirectPosition position = new DirectPosition2D(crs, lon, lat);
        int[] results = (int[]) coverage2D.evaluate(position);
        results = coverage2D.evaluate(position, results);
        return results[0];
    }

    @Override
    public boolean validateCoordinates(double v, double v1) {
        return true;
    }

    @Override
    public double getResolution() {
        return TIFF_RESOLUTION;
    }

    @Override
    public Coordinate[] getBoundary() {
        return new Coordinate[0];
    }


}
