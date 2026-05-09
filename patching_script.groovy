import com.actelion.research.orbit.beans.RawAnnotation
import com.actelion.research.orbit.beans.RawDataFile
import com.actelion.research.orbit.imageAnalysis.components.RecognitionFrame
import com.actelion.research.orbit.imageAnalysis.dal.DALConfig
import com.actelion.research.orbit.imageAnalysis.imaging.TileSizeWrapper
import com.actelion.research.orbit.imageAnalysis.models.IScaleableShape
import com.actelion.research.orbit.imageAnalysis.models.ImageAnnotation
import com.actelion.research.orbit.imageAnalysis.models.ShapeAnnotationList
import com.actelion.research.orbit.imageAnalysis.utils.OrbitImagePlanar
import com.actelion.research.orbit.imageAnalysis.utils.OrbitTiledImageIOrbitImage
import com.actelion.research.orbit.imageAnalysis.utils.OrbitUtils

import javax.imageio.ImageIO
import java.awt.*
import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.util.List
import java.util.ArrayList

def basedir = "d:/export" //save_dir
long total = 0

def images = 1..88 // orbit ids of images

def colorDeconv = "H DAB FastRed"     // if deconvolution is needed
def colorDeconvChannel = 1  // 0=disable, 1=first channel, 2=second channel, 3=comp

images.each {
    int rdfId = it
    println "rdfid: " + rdfId
    RawDataFile rdf = DALConfig.imageProvider.LoadRawDataFile(rdfId)
    List<RawAnnotation> annotations = DALConfig.imageProvider.LoadRawAnnotationsByRawDataFile(rdfId, RawAnnotation.ANNOTATION_TYPE_IMAGE)
    List<ImageAnnotation> objects = new ArrayList<>(annotations.size())
    List<ImageAnnotation> rois = new ArrayList<>()
    List<Shape> exclusions = new ArrayList<>()
    List<Shape> inclusions = new ArrayList<>()
    
    annotations.each {
        ImageAnnotation ia = new ImageAnnotation(it)
        if (ia.subType == ImageAnnotation.SUBTYPE_ROI) rois.add(ia)
        else if (ia.subType == ImageAnnotation.SUBTYPE_NORMAL) objects.add(ia)
        else if (ia.subType == ImageAnnotation.SUBTYPE_EXCLUSION) exclusions.add(ia.getFirstShape())
    }
    total += objects.size()
    RecognitionFrame rf = new RecognitionFrame(rdf)
    println "ROIs: ${rois.size()}"
    println "objects: ${objects.size()}"
    String path = basedir + File.separator + rdfId
    new File(path).mkdirs()

    // 绝对安全的退回：使用你最初能够成功跑通的 OrbitImagePlanar 结构，避免触发 Orbit 内部属性异常
    TileSizeWrapper tileSizeWrapper = new TileSizeWrapper(new OrbitImagePlanar(rf.bimg.image, ""), 2048, 2048)
    OrbitTiledImageIOrbitImage orbitImage = new OrbitTiledImageIOrbitImage(tileSizeWrapper)
    
    rois.each {
        ImageAnnotation ia = it
        IScaleableShape shape = it.getFirstShape()
        shape = shape.getScaledInstance(100d, new Point(0, 0))
        ShapeAnnotationList shapeList = new ShapeAnnotationList(inclusions, exclusions, shape, shape.bounds)
        Point[] tiles = orbitImage.getTileIndices(shape.bounds)
        
        tiles.each { tilePt ->
            if (OrbitUtils.isTileInROI((int) tilePt.x, (int) tilePt.y, orbitImage, shapeList, null)) {
                int tileX = (int) tilePt.x
                int tileY = (int) tilePt.y
                
                Raster tileRaster = orbitImage.getTile(tileX, tileY, 100d, 100d, 0, 0, 0, 0, 0, null, null, null, null, true, true, true, colorDeconvChannel, colorDeconv, null, true, null)
                
                int minX = orbitImage.tileXToX(tileX)
                int minY = orbitImage.tileYToY(tileY)
                tileRaster = tileRaster.createTranslatedChild(0, 0)
                BufferedImage ori = new BufferedImage(orbitImage.colorModel, tileRaster, false, null)
                
                String tileNameSuffix = "_tile" + tileX + "x" + tileY
                ImageIO.write(ori, "jpeg", new File(path + File.separator + rdfId + tileNameSuffix + ".jpg"))

                BufferedImage seg = new BufferedImage(ori.width, ori.height, BufferedImage.TYPE_BYTE_BINARY)
                Graphics2D g2d = seg.createGraphics()

                // repairmen
                g2d.translate(-minX, -minY)
                g2d.setColor(Color.WHITE)
                
                Rectangle tileRect = new Rectangle(minX, minY, ori.width, ori.height)
                
                objects.each { objAnn ->
                    if (objAnn.firstShape != null) {
                        IScaleableShape objectShape = ((IScaleableShape) objAnn.firstShape).getScaledInstance(100d, new Point(0, 0))
                        
                        if (objectShape.bounds.intersects(tileRect)) {
                            g2d.fill(objectShape)
                        }
                    }
                }

                g2d.dispose()
                ImageIO.write(seg, "png", new File(path + File.separator + rdfId + tileNameSuffix + "_seg.png"))
            }
        }
    }
    println "---"
}
println "total: $total"
DALConfig.imageProvider.close()
