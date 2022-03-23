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
     * 高程辅助,返回的图形中的地形极值
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
                return Result.error("当前查询范围：" + String.format("%.2f", area) + "平方公里, " + "范围超出" + heightArea + "平方公里限制!");
            }

        } catch (IOException | ParseException | TransformException e) {
            e.printStackTrace();
            Result.error("GeoJSON输出异常，请检查输入的图形");
        }
        if (oConvertUtils.isEmpty(geometry)) return Result.error("输入图形为空，请检查");

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
     * 高程辅助,返回的图形中的地形极值
     *
     * @param b
     * @param l
     * @param req
     * @return
     */
    @ApiOperation(value = "高程辅助-获取经纬度高程", notes = "高程辅助,获取经纬度高程")
    @GetMapping(value = "/get_point_height")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "b", value = "纬度(y)", required = true, paramType = "double"),
            @ApiImplicitParam(name = "l", value = "经度(x)", required = true, paramType = "double"),
    })
    public Result<?> getBLHeight(
            @RequestParam(name = "b", required = true) double b,
            @RequestParam(name = "l", required = true) double l,
            HttpServletRequest req) {
        return Result.ok(geotoolsTerrainHeight.getHeight(l, b));
    }

    /**
     * 高程辅助,返回DEM文件流
     *
     * @param wkt
     * @param response
     * @param req
     * @return
     */
    @ApiOperation(value = "高程辅助-返回DEM文件流", notes = "高程辅助,返回DEM文件流")
    @GetMapping(value = "/getDem")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "wkt", value = "wkt格式的地理图形", required = true, paramType = "String"),
    })
    public void getDEM(
            @RequestParam(name = "wkt", required = true) String wkt,
            HttpServletRequest req, HttpServletResponse response) {

        try {
            /* 先用geotools给出一个基础图层，但这个基础图层使用nga的tiff包读不到像元尺度，因此再使用nga的tiff包重写 */
            Geometry geometry = reader.read(wkt);
            String prefix = UUID.randomUUID().toString().replace("-", "");//定义临时文件的前缀
            String suffix = ".tif";//定义临时文件的后缀
            File tempOutFile = File.createTempFile(prefix, suffix);
            GridCoverage2D gridCoverage2D = geotoolsCoverageCrop(GRID_COVERAGE_2D, geometry);

            GeoTiffWriter writer = new GeoTiffWriter(tempOutFile, TIFF_HINTS);
            GeoTiffFormat geoTiffFormat = new GeoTiffFormat();
            ParameterValueGroup writeParameters = geoTiffFormat.getWriteParameters();
            List<GeneralParameterValue> valueList = writeParameters.values();
            writer.write(gridCoverage2D, valueList.toArray(new GeneralParameterValue[valueList.size()]));
            writer.dispose();

            /* 使用nga的tiff包重写 */
            TIFFImage tmpImage = TiffReader.readTiff(tempOutFile);
            FileDirectory tmpFileDirectory = tmpImage.getFileDirectory();
            Rasters rasters = tmpFileDirectory.readRasters();
            double[] lowerCorner = gridCoverage2D.getEnvelope().getLowerCorner().getCoordinate();
            double[] upperCorner = gridCoverage2D.getEnvelope().getUpperCorner().getCoordinate();
            //左上角定点
//            double minX = geometry.getEnvelopeInternal().getMinX();
//            double maxY = geometry.getEnvelopeInternal().getMaxY();
//            double minX = (double) map.get("minX");
//            double maxY = (double) map.get("maxY");
            double minX = lowerCorner[0];
            double maxY = upperCorner[1];
            //经纬度转点，X是经度，表宽；y是纬度，表高
            FileDirectory fileDirectory = tiffImage.getFileDirectory();
            //对新影像赋值
            int width = rasters.getWidth(), height = rasters.getHeight();
            Rasters newRaster = new Rasters(width, height, fileDirectory.getSamplesPerPixel(),
                    fileDirectory.getBitsPerSample().get(0), TiffConstants.SAMPLE_FORMAT_SIGNED_INT);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    newRaster.setFirstPixelSample(x, y, rasters.getPixel(x, y)[0]);
                }
            }
            /* 更新参数 */
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
            /* 删除临时文件 */
            tempOutFile.delete();
        } catch (ParseException | IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 将经纬度转成行列号
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
     * 获取原始网格
     * @param geometry 输入的地理图形
     * @param revolution 分辨率
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
                            System.out.println(("获取交点失败！box: " + box + "\r\n geometry:" + geometry));
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
     * 计算数组极值
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
     * 根据几何模型进行影像切割
     *
     * @param coverage2D 原始影像
     * @param geom       几何模型
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
