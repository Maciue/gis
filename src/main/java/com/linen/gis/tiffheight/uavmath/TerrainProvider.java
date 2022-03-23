package com.linen.gis.tiffheight.uavmath;

import org.locationtech.jts.geom.Coordinate;

/**
 * @Author yanll
 * @Date: 2022/3/11 16:12
 * @Description:
 **/
public interface TerrainProvider {
    double getHeight(double var1, double var3);

    boolean validateCoordinates(double var1, double var3);

    double getResolution();

    Coordinate[] getBoundary();
}
