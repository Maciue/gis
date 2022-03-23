package com.linen.gis.tiffheight.config;

import com.linen.gis.tiffheight.uavmath.GeotoolsTerrainHeight;
import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffReader;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.io.File;
import java.io.IOException;

/**
 * @Author yanll
 * @Date: 2022/3/7 16:26
 * @Description:
 **/
@Configuration
public class HeightConfiguration {

    @Value("${dem.file.path}")
    private String demFilePath;
    @Value("${dem.file.min}")
    private String demFileMin;
    @Value("${dem.file.resolution}")
    private Double demFileResolution;

    public static GridCoverage2D GRID_COVERAGE_2D;
    public static Hints TIFF_HINTS;
    public static Double TIFF_RESOLUTION;

    @Bean
    public void gridCoverage2D() throws IOException {
        File file = new File(demFilePath);
        Hints tiffHints = new Hints();
        tiffHints.add(new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE));
        tiffHints.add(new Hints(Hints.DEFAULT_COORDINATE_REFERENCE_SYSTEM, DefaultGeographicCRS.WGS84));
        GeoTiffReader tifReader = new GeoTiffReader(file, tiffHints);
        GridCoverage2D coverage = tifReader.read(null);
        //分辨率，单位°，计算公式（360*（30m/6378137*2*PI））
        TIFF_RESOLUTION = demFileResolution;
        //像元大小
//        TIFF_RESOLUTION = tifReader.getResolutionLevels()[0][0];
        GRID_COVERAGE_2D = coverage;
        TIFF_HINTS = tiffHints;

        //注册gdal
//        gdal.AllRegister();
        // 注册所有的驱动
//        ogr.RegisterAll();
    }

    @Bean
    public GeometryFactory geometryFactory() {
        return new GeometryFactory();
    }

    @Bean
    public TIFFImage tiffImage() {
        File file = new File(demFileMin);
        try {
            return TiffReader.readTiff(file);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Bean
    public WKTReader wktReader() {
        return new WKTReader(geometryFactory());
    }

    @Bean
    public MathTransform mathTransform() throws Exception {
//    public MathTransform mathTransform() throws FactoryException {
        CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:3857");
        MathTransform transform = CRS.findMathTransform(DefaultGeographicCRS.WGS84, targetCRS, false);
        return transform;
    }


    @Bean
    @DependsOn("gridCoverage2D")
    public GeotoolsTerrainHeight geotoolsTerrainHeight() {
        return new GeotoolsTerrainHeight();
    }

    /**
     * 根据系统版本标识换行符的不同，Linux值为\n，其余值为\r\n；
     *
     * @return
     */
    @Bean
    public String wrapType() {
        String wrapType = "\r\n";
        String systemtype = System.getProperty("os.name").toLowerCase();
        if (systemtype.startsWith("linux")) wrapType = "\n";
        return wrapType;

    }

}
