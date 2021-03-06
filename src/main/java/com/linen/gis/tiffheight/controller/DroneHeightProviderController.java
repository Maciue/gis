package com.linen.gis.tiffheight.controller;

import com.linen.gis.tiffheight.api.Result;
import com.linen.gis.tiffheight.api.oConvertUtils;
import com.linen.gis.tiffheight.uavmath.GeotoolsTerrainHeight;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import mil.nga.tiff.*;
import mil.nga.tiff.util.TiffConstants;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geojson.geom.GeometryJSON;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.json.simple.JSONObject;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.linen.gis.tiffheight.config.HeightConfiguration.*;


/**
 * @Author yanll
 * @Date: 2020/7/21 17:23
 * @Description:
 **/
@RestController
@RequestMapping("/heightProvider")
public class DroneHeightProviderController {
    @Autowired
    WKTReader reader;
    @Autowired
    MathTransform mathTransform;
    @Autowired
    GeometryFactory geometryFactory;
    @Autowired
    TIFFImage tiffImage;
    @Autowired
    GeotoolsTerrainHeight geotoolsTerrainHeight;

    @Value("${dem.height.area}")
    private Integer heightArea;


    /**
     * ????????????,?????????????????????????????????
     *
     * @param geometryString
     * @param geometryType
     * @param req
     * @return
     */
    @GetMapping(value = "/region_extremum")
    public Result<?> ConventionalTrajectoryPlanning(
            @RequestParam(name = "geometry", required = true) String geometryString,
            @RequestParam(name = "geometry_type", required = true, defaultValue = "wkt") String geometryType,
            HttpServletRequest req) {
        GeometryJSON geometryJSON = new GeometryJSON();
        Geometry geometry = null;
        try {
            if (geometryType.equals("geojson")) {
                geometry = geometryJSON.readPolygon(new ByteArrayInputStream(geometryString.getBytes()));
            } else {
                geometry = reader.read(geometryString);
            }
            Geometry geometryMercator = JTS.transform(geometry, mathTransform);
            double area = geometryMercator.getArea() / 1000000;
            if (area > heightArea) {
                return Result.error("?????????????????????" + String.format("%.2f", area) + "????????????, " + "????????????" + heightArea + "??????????????????!");
            }

        } catch (IOException | ParseException | TransformException e) {
            e.printStackTrace();
            Result.error("GeoJSON???????????????????????????????????????");
        }
        if (oConvertUtils.isEmpty(geometry)) return Result.error("??????????????????????????????");

        Geometry gridPolygon = getRegionGrid(geometry, TIFF_RESOLUTION);

        Coordinate[] coordinates = gridPolygon.getCoordinates();
        int arrLength = coordinates.length;
        double[] heightValueArr = new double[arrLength];
        for (int i = 0; i < arrLength; i++) {
            Coordinate coordinate = coordinates[i];
            heightValueArr[i] = geotoolsTerrainHeight.getHeight(coordinate.x, coordinate.y);
        }

        return Result.ok(maxAndMin(heightValueArr, arrLength));
    }

    /**
     * ????????????,?????????????????????????????????
     *
     * @param b
     * @param l
     * @param req
     * @return
     */
    @ApiOperation(value = "????????????-?????????????????????", notes = "????????????,?????????????????????")
    @GetMapping(value = "/get_point_height")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "b", value = "??????(y)", required = true, paramType = "double"),
            @ApiImplicitParam(name = "l", value = "??????(x)", required = true, paramType = "double"),
    })
    public Result<?> getBLHeight(
            @RequestParam(name = "b", required = true) double b,
            @RequestParam(name = "l", required = true) double l,
            HttpServletRequest req) {
        return Result.ok(geotoolsTerrainHeight.getHeight(l, b));
    }

    /**
     * ????????????,??????DEM?????????
     *
     * @param wkt
     * @param response
     * @param req
     * @return
     */
    @ApiOperation(value = "????????????-??????DEM?????????", notes = "????????????,??????DEM?????????")
    @GetMapping(value = "/getDem")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "wkt", value = "wkt?????????????????????", required = true, paramType = "String"),
    })
    public void getDEM(
            @RequestParam(name = "wkt", required = true) String wkt,
            HttpServletRequest req, HttpServletResponse response) {

        try {
            /* ??????geotools??????????????????????????????????????????????????????nga???tiff??????????????????????????????????????????nga???tiff????????? */
            Geometry geometry = reader.read(wkt);
            String prefix = UUID.randomUUID().toString().replace("-", "");//???????????????????????????
            String suffix = ".tif";//???????????????????????????
            File tempOutFile = File.createTempFile(prefix, suffix);
            GridCoverage2D gridCoverage2D = geotoolsCoverageCrop(GRID_COVERAGE_2D, geometry);

            GeoTiffWriter writer = new GeoTiffWriter(tempOutFile, TIFF_HINTS);
            GeoTiffFormat geoTiffFormat = new GeoTiffFormat();
            ParameterValueGroup writeParameters = geoTiffFormat.getWriteParameters();
            List<GeneralParameterValue> valueList = writeParameters.values();
            writer.write(gridCoverage2D, valueList.toArray(new GeneralParameterValue[valueList.size()]));
            writer.dispose();

            /* ??????nga???tiff????????? */
            TIFFImage tmpImage = TiffReader.readTiff(tempOutFile);
            FileDirectory tmpFileDirectory = tmpImage.getFileDirectory();
            Rasters rasters = tmpFileDirectory.readRasters();
            double[] lowerCorner = gridCoverage2D.getEnvelope().getLowerCorner().getCoordinate();
            double[] upperCorner = gridCoverage2D.getEnvelope().getUpperCorner().getCoordinate();
            //???????????????
//            double minX = geometry.getEnvelopeInternal().getMinX();
//            double maxY = geometry.getEnvelopeInternal().getMaxY();
//            double minX = (double) map.get("minX");
//            double maxY = (double) map.get("maxY");
            double minX = lowerCorner[0];
            double maxY = upperCorner[1];
            //??????????????????X?????????????????????y??????????????????
            FileDirectory fileDirectory = tiffImage.getFileDirectory();
            //??????????????????
            int width = rasters.getWidth(), height = rasters.getHeight();
            Rasters newRaster = new Rasters(width, height, fileDirectory.getSamplesPerPixel(),
                    fileDirectory.getBitsPerSample().get(0), TiffConstants.SAMPLE_FORMAT_SIGNED_INT);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    newRaster.setFirstPixelSample(x, y, rasters.getPixel(x, y)[0]);
                }
            }
            /* ???????????? */
            int rowsPerStrip = newRaster.calculateRowsPerStrip(TiffConstants.PLANAR_CONFIGURATION_PLANAR);
            fileDirectory.setRowsPerStrip(rowsPerStrip);
            fileDirectory.setImageHeight(height);
            fileDirectory.setImageWidth(width);
            fileDirectory.setWriteRasters(newRaster);
            List<Double> doubles = new ArrayList<>();
            doubles.add(0.00);
            doubles.add(0.00);
            doubles.add(0.00);
            doubles.add(minX);
            doubles.add(maxY);
            doubles.add(0.00);
            FileDirectoryEntry fileDirectoryEntry = new FileDirectoryEntry(FieldTagType.ModelTiepoint, FieldType.DOUBLE, 6, doubles);
            fileDirectory.addEntry(fileDirectoryEntry);
            TIFFImage tiffImage = new TIFFImage();
            tiffImage.add(fileDirectory);
            byte[] tiffBytes = TiffWriter.writeTiffToBytes(tiffImage);
            response.getOutputStream().write(tiffBytes, 0, tiffBytes.length);
            response.getOutputStream().flush();
            /* ?????????????????? */
            tempOutFile.delete();
        } catch (ParseException | IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * ???????????????????????????
     * @param fileDirectory
     * @param latX
     * @param lonY
     * @return
     */
    public int[] transLonLat2ColRow(FileDirectory fileDirectory, double latX, double lonY) {

        List<Double> tiepoint = fileDirectory.getModelTiepoint();
        List<Double> modelPixelScale = fileDirectory.getModelPixelScale();
        final double px = (latX - tiepoint.get(3)) / modelPixelScale.get(0) + tiepoint.get(0);
        final double py = (tiepoint.get(4) - lonY) / modelPixelScale.get(1) + tiepoint.get(1);
        return new int[]{(int) px, (int) py};
    }

    /**
     * ??????????????????
     * @param geometry ?????????????????????
     * @param revolution ?????????
     * @return
     */
    private Geometry getRegionGrid(Geometry geometry, double revolution) {
        Envelope rect = geometry.getEnvelopeInternal();
        double height = rect.getHeight();
        double width = rect.getWidth();
        int numX = (int) Math.ceil(width / revolution);
        int numY = (int) Math.ceil(height / revolution);
        double dx = (width - numX * revolution) / 2.0;
        double dy = (height - numY * revolution) / 2.0;
        Geometry[][] nodes = new Geometry[numX][numY];
        for (int i = 0; i < numX; ++i) {
            for (int j = 0; j < numY; ++j) {
                double minX = dx + rect.getMinX() + i * revolution;
                double minY = dy + rect.getMinY() + j * revolution;
                double maxX = minX + revolution;
                double maxY = minY + revolution;
                Coordinate coord0 = new Coordinate(minX, minY);
                Coordinate coord2 = new Coordinate(maxX, minY);
                Coordinate coord3 = new Coordinate(maxX, maxY);
                Coordinate coord4 = new Coordinate(minX, maxY);
                Geometry box = geometryFactory.createPolygon(new Coordinate[]{coord0, coord2, coord3, coord4, coord0});
                if (box.intersects(geometry)) {
                    Geometry region = null;
                    try {
                        region = geometry.intersection(box);
                    } catch (Exception e) {
                        e.printStackTrace();
                        try {
                            region = box.intersection(geometry);
                        } catch (Exception ee) {
                            e.printStackTrace();
                            System.out.println(("?????????????????????box: " + box + "\r\n geometry:" + geometry));
                        }
                    }
                    nodes[i][j] = region;
                }
            }
        }
        List<Geometry> list = new ArrayList<Geometry>();
        for (int l = 0; l < numX; ++l) {
            for (int m = 0; m < numY; ++m) {
                Geometry region2 = nodes[l][m];
                if (region2 != null) {
                    list.add(region2);
                }
            }
        }
        return geometryFactory.buildGeometry(list);
    }

    /**
     * ??????????????????
     *
     * @param a
     * @param length
     * @return
     */
    public JSONObject maxAndMin(double[] a, int length) {
        JSONObject jsonObject = new JSONObject();
        double Max, Min;
        double[] b, c;
        if (length % 2 == 0) {
            b = new double[length / 2];
            c = new double[length / 2];
        } else {
            b = new double[length / 2 + 1];
            c = new double[length / 2 + 1];
            b[length / 2] = a[length - 1];
            c[length / 2] = a[length - 1];
        }
        for (int i = 0, j = 0; i < length - 1; i += 2, j++) {
            if (a[i] >= a[i + 1]) {
                b[j] = a[i];
                c[j] = a[i + 1];
            } else {
                c[j] = a[i];
                b[j] = a[i + 1];
            }
        }

        Max = b[0];
        Min = c[0];
        for (int i = 1; i < b.length; i++) {
            if (Max < b[i]) Max = b[i];
        }
        for (int i = 1; i < c.length; i++) {
            if (Min > c[i]) Min = c[i];
        }
        jsonObject.put("max", Max);
        jsonObject.put("min", Min);
        jsonObject.put("avg", Double.valueOf(String.format("%.2f", Arrays.stream(a).average().orElse(Double.NaN))));

        return jsonObject;
    }


    /**
     * ????????????????????????????????????
     *
     * @param coverage2D ????????????
     * @param geom       ????????????
     */
    public static GridCoverage2D geotoolsCoverageCrop(GridCoverage2D coverage2D, Geometry geom) {
        org.opengis.geometry.Envelope envelope = new Envelope2D();
        ((Envelope2D) envelope).setCoordinateReferenceSystem(DefaultGeographicCRS.WGS84);
        Envelope jtsEnv = geom.getEnvelopeInternal();
        ((Envelope2D) envelope).height = jtsEnv.getHeight();
        ((Envelope2D) envelope).width = jtsEnv.getWidth();
        ((Envelope2D) envelope).x = jtsEnv.getMinX();
        ((Envelope2D) envelope).y = jtsEnv.getMinY();
        CoverageProcessor processor = CoverageProcessor.getInstance();
        // An example of manually creating the operation and parameters we want
        final ParameterValueGroup param = processor.getOperation("CoverageCrop").getParameters();
        param.parameter("Source").setValue(coverage2D);
        param.parameter("Envelope").setValue(envelope);

        return (GridCoverage2D) processor.doOperation(param);
    }


}
