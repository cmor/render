package org.janelia.render.service.dao;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.StackMetaData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.service.ObjectNotFoundException;
import org.janelia.render.service.StackId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.BulkWriteOperation;
import com.mongodb.BulkWriteResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.QueryOperators;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;

/**
 * Data access object for Render database.
 *
 * @author Eric Trautman
 */
public class RenderDao {

    public static final String RENDER_DB_NAME = "render";

    private final DB renderDb;

    public RenderDao(final MongoClient client) {
        renderDb = client.getDB(RENDER_DB_NAME);
    }

    /**
     * @return a render parameters object for the specified stack.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public RenderParameters getParameters(final StackId stackId,
                                          final Double x,
                                          final Double y,
                                          final Double z,
                                          final Integer width,
                                          final Integer height,
                                          final Double scale)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);
        validateRequiredParameter("scale", scale);

        final double lowerRightX = x + width;
        final double lowerRightY = y + height;
        final DBObject tileQuery = getIntersectsBoxQuery(z, x, y, lowerRightX, lowerRightY);

        final RenderParameters renderParameters = new RenderParameters(null, x, y, width, height, scale);
        addResolvedTileSpecs(stackId, tileQuery, renderParameters);

        // TODO: is returning black image okay or do we want to throw an exception?
//        if (! renderParameters.hasTileSpecs()) {
//            throw new IllegalArgumentException("no tile specifications found");
//        }

        return renderParameters;
    }

    /**
     * @return number of tiles within the specified bounding box.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public int getTileCount(final StackId stackId,
                            final Double x,
                            final Double y,
                            final Double z,
                            final Integer width,
                            final Integer height)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);
        validateRequiredParameter("width", width);
        validateRequiredParameter("height", height);

        final DBCollection tileCollection = getTileCollection(stackId);

        final double lowerRightX = x + width;
        final double lowerRightY = y + height;
        final DBObject tileQuery = getIntersectsBoxQuery(z, x, y, lowerRightX, lowerRightY);

        final int count = tileCollection.find(tileQuery).count();

        LOG.debug("getTileCount: found {} tile spec(s) for {}.{}.find({})",
                  count, RENDER_DB_NAME, tileCollection.getName(), tileQuery);

        return count;
    }

    /**
     * @return the specified tile spec.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     *
     * @throws ObjectNotFoundException
     *   if a spec with the specified z and tileId cannot be found.
     */
    public TileSpec getTileSpec(final StackId stackId,
                                final String tileId,
                                final boolean resolveTransformReferences)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("tileId", tileId);

        final DBCollection tileCollection = getTileCollection(stackId);

        final BasicDBObject query = new BasicDBObject();
        query.put("tileId", tileId);

        LOG.debug("getTileSpec: {}.{}.find({})",
                  tileCollection.getDB().getName(), tileCollection.getName(), query);

        final DBObject document = tileCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("tile spec with id '" + tileId + "' does not exist in the " +
                                              tileCollection.getFullName() + " collection");
        }

        final TileSpec tileSpec = TileSpec.fromJson(document.toString());

        if (resolveTransformReferences) {
            resolveTransformReferencesForTiles(stackId, Arrays.asList(tileSpec));
        }

        return tileSpec;
    }

    /**
     * @return the specified tile spec with its transform references resolved.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     */
    public TileSpec resolveTransformReferencesForTiles(final StackId stackId,
                                                       final TileSpec tileSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("tileSpec", tileSpec);

        resolveTransformReferencesForTiles(stackId, Arrays.asList(tileSpec));

        return tileSpec;
    }

    public Map<String, TransformSpec> resolveTransformReferencesForTiles(final StackId stackId,
                                                                         final List<TileSpec> tileSpecs)
            throws IllegalStateException {

        final Set<String> unresolvedIds = new HashSet<>();
        ListTransformSpec transforms;
        for (final TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            if (transforms != null) {
                transforms.addUnresolvedIds(unresolvedIds);
            }
        }

        final Map<String, TransformSpec> resolvedIdToSpecMap = new HashMap<>();

        final int unresolvedCount = unresolvedIds.size();
        if (unresolvedCount > 0) {

            final DBCollection transformCollection = getTransformCollection(stackId);
            getDataForTransformSpecReferences(transformCollection, unresolvedIds, resolvedIdToSpecMap, 1);

            // resolve any references within the retrieved transform specs
            for (final TransformSpec transformSpec : resolvedIdToSpecMap.values()) {
                transformSpec.resolveReferences(resolvedIdToSpecMap);
            }

            // apply fully resolved transform specs to tiles
            for (final TileSpec tileSpec : tileSpecs) {
                transforms = tileSpec.getTransforms();
                transforms.resolveReferences(resolvedIdToSpecMap);
                if (! transforms.isFullyResolved()) {
                    throw new IllegalStateException("tile spec " + tileSpec.getTileId() +
                                                    " is not fully resolved after applying the following transform specs: " +
                                                    resolvedIdToSpecMap.keySet());
                }
            }

        }

        return resolvedIdToSpecMap;
    }


    /**
     * @return a list of resolved tile specifications for all tiles that encompass the specified coordinates.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing, if the stack cannot be found, or
     *   if no tile can be found that encompasses the coordinates.
     */
    public List<TileSpec> getTileSpecs(final StackId stackId,
                                       final Double x,
                                       final Double y,
                                       final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("x", x);
        validateRequiredParameter("y", y);
        validateRequiredParameter("z", z);

        final DBObject tileQuery = getIntersectsBoxQuery(z, x, y, x, y);
        final RenderParameters renderParameters = new RenderParameters();
        addResolvedTileSpecs(stackId, tileQuery, renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +
                                               " for world coordinates x=" + x + ", y=" + y + ", z=" + z);
        }

        return renderParameters.getTileSpecs();
    }

    public void writeCoordinatesWithTileIds(final StackId stackId,
                                            final Double z,
                                            final List<TileCoordinates> worldCoordinatesList,
                                            final OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        LOG.debug("writeCoordinatesWithTileIds: entry, stackId={}, z={}, worldCoordinatesList.size()={}",
                  stackId, z, worldCoordinatesList.size());

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);
        final DBObject tileKeys = new BasicDBObject("tileId", 1).append("_id", 0);

        // order tile specs by tileId to ensure consistent coordinate mapping
        final DBObject orderBy = new BasicDBObject("tileId", 1);

        final ProcessTimer timer = new ProcessTimer();
        final byte[] openBracket = "[".getBytes();
        final byte[] comma = ",".getBytes();
        final byte[] closeBracket = "]".getBytes();

        int coordinateCount = 0;

        double[] world;
        DBObject tileQuery = null;
        DBCursor cursor = null;
        DBObject document;
        Object tileId;
        String coordinatesJson;
        try {

            outputStream.write(openBracket);

            TileCoordinates worldCoordinates;
            for (int i = 0; i < worldCoordinatesList.size(); i++) {

                worldCoordinates = worldCoordinatesList.get(i);
                world = worldCoordinates.getWorld();

                if (world == null) {
                    throw new IllegalArgumentException("world values are missing for element " + i);
                } else if (world.length < 2) {
                    throw new IllegalArgumentException("world values must include both x and y for element " + i);
                }

                tileQuery = getIntersectsBoxQuery(z, world[0], world[1], world[0], world[1]);
                cursor = tileCollection.find(tileQuery, tileKeys);
                cursor.sort(orderBy);

                if (i > 0) {
                    outputStream.write(comma);
                }
                outputStream.write(openBracket);

                if (cursor.hasNext()) {

                    document = cursor.next();
                    tileId = document.get("tileId");
                    if (tileId != null) {
                        worldCoordinates.setTileId(tileId.toString());
                    }
                    coordinatesJson = worldCoordinates.toJson();
                    outputStream.write(coordinatesJson.getBytes());

                    while (cursor.hasNext()) {
                        document = cursor.next();
                        tileId = document.get("tileId");
                        if (tileId != null) {
                            worldCoordinates.setTileId(tileId.toString());
                        }
                        coordinatesJson = worldCoordinates.toJson();

                        outputStream.write(comma);
                        outputStream.write(coordinatesJson.getBytes());
                    }

                } else {

                    coordinatesJson = worldCoordinates.toJson();
                    outputStream.write(coordinatesJson.getBytes());

                }

                cursor.close();

                outputStream.write(closeBracket);

                coordinateCount++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeCoordinatesWithTileIds: data written for {} coordinates", coordinateCount);
                }
            }

            outputStream.write(closeBracket);

        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        LOG.debug("writeCoordinatesWithTileIds: wrote data for {} coordinates returned by queries like {}.find({},{}).sort({}), elapsedSeconds={}",
                  coordinateCount, tileCollection.getFullName(), tileQuery, tileKeys, orderBy, timer.getElapsedSeconds());
    }

    /**
     * @return a list of resolved tile specifications for all tiles that have the specified z.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or if the stack cannot be found, or
     *   if no tile can be found for the specified z.
     */
    public List<TileSpec> getTileSpecs(final StackId stackId,
                                       final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBObject tileQuery = new BasicDBObject("z", z);
        final RenderParameters renderParameters = new RenderParameters();
        addResolvedTileSpecs(stackId, tileQuery, renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +" for z=" + z);
        }

        return renderParameters.getTileSpecs();
    }

    /**
     * @return a resolved tile spec collection for all tiles that have the specified z.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or if the stack cannot be found, or
     *   if no tile can be found for the specified z.
     */
    public ResolvedTileSpecCollection getResolvedTiles(final StackId stackId,
                                                       final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBObject tileQuery = new BasicDBObject("z", z);
        final RenderParameters renderParameters = new RenderParameters();
        final Map<String, TransformSpec> resolvedIdToSpecMap = addResolvedTileSpecs(stackId,
                                                                                    tileQuery,
                                                                                    renderParameters);

        if (! renderParameters.hasTileSpecs()) {
            throw new IllegalArgumentException("no tile specifications found in " + stackId +" for z=" + z);
        }

        return new ResolvedTileSpecCollection(stackId.getStack(),
                                              z,
                                              resolvedIdToSpecMap.values(),
                                              renderParameters.getTileSpecs());
    }

    /**
     * Saves the specified tile spec to the database.
     *
     * @param  stackId            stack identifier.
     * @param  resolvedTileSpecs  collection of resolved tile specs (with referenced transforms).
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public void saveResolvedTiles(final StackId stackId,
                                  final ResolvedTileSpecCollection resolvedTileSpecs)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("resolvedTileSpecs", resolvedTileSpecs);

        final Collection<TransformSpec> transformSpecs = resolvedTileSpecs.getTransformSpecs();
        final Collection<TileSpec> tileSpecs = resolvedTileSpecs.getTileSpecs();

        if (transformSpecs.size() > 0) {

            final DBCollection transformCollection = getTransformCollection(stackId);

            // TODO: remove transform index creation when stack creation process is finalized
            ensureTransformIndexes(transformCollection);

            final BulkWriteOperation bulk = transformCollection.initializeUnorderedBulkOperation();


            BasicDBObject query = null;
            DBObject transformSpecObject;
            for (final TransformSpec transformSpec : transformSpecs) {
                query = new BasicDBObject("id", transformSpec.getId());
                transformSpecObject = (DBObject) JSON.parse(transformSpec.toJson());
                bulk.find(query).upsert().replaceOne(transformSpecObject);
            }

            final BulkWriteResult result = bulk.execute();

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = getBulkResultMessage("transform specs", result, transformSpecs.size());
                LOG.debug("saveResolvedTiles: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, transformCollection.getFullName(), query);
            }
        }

        if (tileSpecs.size() > 0) {

            final DBCollection tileCollection = getTileCollection(stackId);

            // TODO: remove tile index creation when stack creation process is finalized
            ensureTileIndexes(tileCollection);

            final BulkWriteOperation bulkTileOperation = tileCollection.initializeUnorderedBulkOperation();

            BasicDBObject query = null;
            DBObject tileSpecObject;
            for (final TileSpec tileSpec : tileSpecs) {
                query = new BasicDBObject("tileId", tileSpec.getTileId());
                tileSpecObject = (DBObject) JSON.parse(tileSpec.toJson());
                bulkTileOperation.find(query).upsert().replaceOne(tileSpecObject);
            }

            final BulkWriteResult result = bulkTileOperation.execute();

            if (LOG.isDebugEnabled()) {
                final String bulkResultMessage = getBulkResultMessage("tile specs", result, tileSpecs.size());
                LOG.debug("saveResolvedTiles: {} using {}.initializeUnorderedBulkOp()",
                          bulkResultMessage, tileCollection.getFullName(), query);
            }
        }

    }

    /**
     * Saves the specified tile spec to the database.
     *
     * @param  stackId    stack identifier.
     * @param  tileSpec   specification to be saved.
     *
     * @return the specification updated with any attributes that were modified by the save.
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public TileSpec saveTileSpec(final StackId stackId,
                                 final TileSpec tileSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("tileSpec", tileSpec);
        validateRequiredParameter("tileSpec.tileId", tileSpec.getTileId());

        final DBCollection tileCollection = getTileCollection(stackId);

        final String context = "tile spec with id '" + tileSpec.getTileId();
        validateTransformReferences(context, stackId, tileSpec.getTransforms());

        final BasicDBObject query = new BasicDBObject();
        query.put("tileId", tileSpec.getTileId());

        final DBObject tileSpecObject = (DBObject) JSON.parse(tileSpec.toJson());

        final WriteResult result = tileCollection.update(query, tileSpecObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
        }

        LOG.debug("saveTileSpec: {}.{},({}), upsertedId is {}",
                  tileCollection.getFullName(), action, query, result.getUpsertedId());

        return tileSpec;
    }

    /**
     * @return the specified transform spec.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing.
     *
     * @throws ObjectNotFoundException
     *   if a spec with the specified transformId cannot be found.
     */
    public TransformSpec getTransformSpec(final StackId stackId,
                                          final String transformId)
            throws IllegalArgumentException,
                   ObjectNotFoundException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("transformId", transformId);

        final DBCollection transformCollection = getTransformCollection(stackId);

        final BasicDBObject query = new BasicDBObject();
        query.put("id", transformId);

        LOG.debug("getTransformSpec: {}.find({})", transformCollection.getFullName(), query);

        final DBObject document = transformCollection.findOne(query);

        if (document == null) {
            throw new ObjectNotFoundException("transform spec with id '" + transformId + "' does not exist in the " +
                                              transformCollection.getFullName() + " collection");
        }

        return JsonUtils.GSON.fromJson(document.toString(), TransformSpec.class);
    }

    /**
     * Saves the specified transform spec to the database.
     *
     * @param  stackId        stack identifier.
     * @param  transformSpec  specification to be saved.
     *
     * @return the specification updated with any attributes that were modified by the save.
     *
     * @throws IllegalArgumentException
     *   if any required parameters or transform spec references are missing.
     */
    public TransformSpec saveTransformSpec(final StackId stackId,
                                           final TransformSpec transformSpec)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("transformSpec", transformSpec);
        validateRequiredParameter("transformSpec.id", transformSpec.getId());

        final DBCollection transformCollection = getTransformCollection(stackId);

        final String context = "transform spec with id '" + transformSpec.getId() + "'";
        validateTransformReferences(context, stackId, transformSpec);

        final BasicDBObject query = new BasicDBObject();
        query.put("id", transformSpec.getId());

        final DBObject transformSpecObject = (DBObject) JSON.parse(transformSpec.toJson());

        final WriteResult result = transformCollection.update(query, transformSpecObject, true, false);

        String action;
        if (result.isUpdateOfExisting()) {
            action = "update";
        } else {
            action = "insert";
        }

        LOG.debug("saveTransformSpec: {}.{},({}), upsertedId is {}",
                  transformCollection.getFullName(), action, query, result.getUpsertedId());

        return transformSpec;
    }

    /**
     * @return list of databases for the specified owner.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public List<StackId> getStackIds(final String owner)
            throws IllegalArgumentException {

        validateRequiredParameter("owner", owner);

        final List<StackId> list = new ArrayList<>();
        for (final String name : renderDb.getCollectionNames()) {
            if (name.startsWith(owner) && name.endsWith(StackId.TILE_COLLECTION_SUFFIX)) {
                list.add(StackId.fromCollectionName(name));
            }
        }

        Collections.sort(list);

        LOG.debug("getStackIds: returning {}", list);

        return list;
    }

    /**
     * @return list of distinct z values (layers) for the specified stackId.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public List<Double> getZValues(final StackId stackId)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        final DBCollection tileCollection = getTileCollection(stackId);

        final List<Double> list = new ArrayList<>();
        for (final Object zValue : tileCollection.distinct("z")) {
            list.add(new Double(zValue.toString()));
        }

        LOG.debug("getZValues: returning {} values for {}", list.size(), tileCollection.getFullName());

        return list;
    }

    /**
     * @return meta data for the specified stack.
     *
     * @throws IllegalArgumentException
     *   if the stack cannot be found.
     */
    public StackMetaData getStackMetaData(final StackId stackId)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        final DBCollection stackCollection = getStackCollection(stackId);

        StackMetaData stackMetaData;

        final DBObject document = stackCollection.findOne();
        if (document == null) {
            stackMetaData = new StackMetaData();
        } else {
            stackMetaData = StackMetaData.fromJson(document.toString());
        }

        return stackMetaData;
    }

    /**
     * @return coordinate bounds for all tiles in the specified stack.
     *
     * @throws IllegalArgumentException
     *   if the stack cannot be found.
     */
    public Bounds getStackBounds(final StackId stackId)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);

        final DBCollection tileCollection = getTileCollection(stackId);
        final DBObject tileQuery = new BasicDBObject();

        final Double minX = getBound(tileCollection, tileQuery, "minX", true);
        final Double minY = getBound(tileCollection, tileQuery, "minY", true);
        final Double minZ = getBound(tileCollection, tileQuery, "z", true);
        final Double maxX = getBound(tileCollection, tileQuery, "maxX", false);
        final Double maxY = getBound(tileCollection, tileQuery, "maxY", false);
        final Double maxZ = getBound(tileCollection, tileQuery, "z", false);

        final Bounds bounds = new Bounds(minX, minY, maxX, maxY);
        bounds.setMinZ(minZ);
        bounds.setMaxZ(maxZ);

        return bounds;
    }

    /**
     * @return coordinate bounds for all tiles in the specified stack layer.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public Bounds getLayerBounds(final StackId stackId,
                                 final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);
        final DBObject tileQuery = new BasicDBObject("z", z);

        final Double minX = getBound(tileCollection, tileQuery, "minX", true);

        if (minX == null) {
            throw new IllegalArgumentException("stack " + stackId.getStack() +
                                               " does not contain any tiles with a z value of " + z);
        }

        final Double minY = getBound(tileCollection, tileQuery, "minY", true);
        final Double maxX = getBound(tileCollection, tileQuery, "maxX", false);
        final Double maxY = getBound(tileCollection, tileQuery, "maxY", false);

        return new Bounds(minX, minY, maxX, maxY);
    }

    /**
     * @return spatial data for all tiles in the specified stack layer.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     */
    public List<TileBounds> getTileBounds(final StackId stackId,
                                          final Double z)
            throws IllegalArgumentException {

        validateRequiredParameter("stackId", stackId);
        validateRequiredParameter("z", z);

        final DBCollection tileCollection = getTileCollection(stackId);

        final DBObject tileQuery = new BasicDBObject("z", z);
        final DBObject tileKeys =
                new BasicDBObject("tileId", 1).append("minX", 1).append("minY", 1).append("maxX", 1).append("maxY", 1);

        final List<TileBounds> list = new ArrayList<>();

        try (DBCursor cursor = tileCollection.find(tileQuery, tileKeys)) {
            DBObject document;
            while (cursor.hasNext()) {
                document = cursor.next();
                list.add(TileBounds.fromJson(document.toString()));
            }
        }

        LOG.debug("getTileBounds: found {} tile spec(s) for {}.find({},{})",
                  list.size(), tileCollection.getFullName(), tileQuery, tileKeys);

        return list;
    }

    /**
     * Writes the layout file data for the specified stack to the specified stream.
     *
     * @param  stackId          stack identifier.
     * @param  stackRequestUri  the base stack request URI for building tile render-parameter URIs.
     * @param  minZ             the minimum z to include (or null if no minimum).
     * @param  maxZ             the maximum z to include (or null if no maximum).
     * @param  outputStream     stream to which layout file data is to be written.
     *
     * @throws IllegalArgumentException
     *   if any required parameters are missing or the stack cannot be found.
     *
     * @throws IOException
     *   if the data cannot be written for any reason.
     */
    public void writeLayoutFileData(final StackId stackId,
                                    final String stackRequestUri,
                                    final Double minZ,
                                    final Double maxZ,
                                    final OutputStream outputStream)
            throws IllegalArgumentException, IOException {

        LOG.debug("writeLayoutFileData: entry, stackId={}, minZ={}, maxZ={}",
                  stackId, minZ, maxZ);

        validateRequiredParameter("stackId", stackId);

        final DBCollection tileCollection = getTileCollection(stackId);

        BasicDBObject zFilter = null;
        if (minZ != null) {
            zFilter = new BasicDBObject(QueryOperators.GTE, minZ);
            if (maxZ != null) {
                zFilter = zFilter.append(QueryOperators.LTE, maxZ);
            }
        } else if (maxZ != null) {
            zFilter = new BasicDBObject(QueryOperators.LTE, maxZ);
        }

        BasicDBObject tileQuery;
        if (zFilter == null) {
            tileQuery = new BasicDBObject();
        } else {
            tileQuery = new BasicDBObject("z", zFilter);
        }

        final DBObject tileKeys =
                new BasicDBObject("tileId", 1).append("z", 1).append("minX", 1).append("minY", 1).append("layout", 1).append("mipmapLevels", 1);

        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        final DBObject orderBy = new BasicDBObject("z", 1).append("minY", 1).append("minX", 1);
        try (DBCursor cursor = tileCollection.find(tileQuery, tileKeys)) {
            final String baseUriString = '\t' + stackRequestUri + "/tile/";

            cursor.sort(orderBy);

            DBObject document;
            TileSpec tileSpec;
            String layoutData;
            String uriString;
            while (cursor.hasNext()) {
                document = cursor.next();
                tileSpec = TileSpec.fromJson(document.toString());
                layoutData = tileSpec.toLayoutFileFormat();
                outputStream.write(layoutData.getBytes());

                // {stackRequestUri}/tile/{tileId}/render-parameters
                uriString = baseUriString + tileSpec.getTileId() + "/render-parameters" + "\n";
                outputStream.write(uriString.getBytes());
                tileSpecCount++;

                if (timer.hasIntervalPassed()) {
                    LOG.debug("writeLayoutFileData: data written for {} tiles", tileSpecCount);
                }

            }
        }

        LOG.debug("writeLayoutFileData: wrote data for {} tile spec(s) returned by {}.find({},{}).sort({}), elapsedSeconds={}",
                  tileSpecCount, tileCollection.getFullName(), tileQuery, tileKeys, orderBy, timer.getElapsedSeconds());
    }

    private List<TransformSpec> getTransformSpecs(final DBCollection transformCollection,
                                                  final Set<String> specIds) {
        final int specCount = specIds.size();
        final List<TransformSpec> transformSpecList = new ArrayList<>(specCount);
        if (specCount > 0) {

            final BasicDBObject transformQuery = new BasicDBObject();
            transformQuery.put("id", new BasicDBObject(QueryOperators.IN, specIds));

            LOG.debug("getTransformSpecs: {}.find({})", transformCollection.getFullName(), transformQuery);

            try (DBCursor cursor = transformCollection.find(transformQuery)) {
                DBObject document;
                TransformSpec transformSpec;
                while (cursor.hasNext()) {
                    document = cursor.next();
                    transformSpec = JsonUtils.GSON.fromJson(document.toString(), TransformSpec.class);
                    transformSpecList.add(transformSpec);
                }
            }

        }

        return transformSpecList;
    }

    private void getDataForTransformSpecReferences(final DBCollection transformCollection,
                                                   final Set<String> unresolvedSpecIds,
                                                   final Map<String, TransformSpec> resolvedIdToSpecMap,
                                                   final int callCount) {

        if (callCount > 10) {
            throw new IllegalStateException(callCount + " passes have been made to resolve transform references, " +
                                            "exiting in case the data is overly nested or there is a recursion error");
        }

        final int specCount = unresolvedSpecIds.size();
        if (specCount > 0) {

            final List<TransformSpec> transformSpecList = getTransformSpecs(transformCollection,
                                                                            unresolvedSpecIds);

            LOG.debug("resolveTransformSpecReferences: on pass {} retrieved {} transform specs",
                      callCount, transformSpecList.size());

            final Set<String> newlyUnresolvedSpecIds = new HashSet<>();

            for (final TransformSpec spec : transformSpecList) {
                resolvedIdToSpecMap.put(spec.getId(), spec);
                for (final String id : spec.getUnresolvedIds()) {
                    if ((! resolvedIdToSpecMap.containsKey(id)) && (! unresolvedSpecIds.contains(id))) {
                        newlyUnresolvedSpecIds.add(id);
                    }
                }
            }

            if (newlyUnresolvedSpecIds.size() > 0) {
                getDataForTransformSpecReferences(transformCollection,
                                                  newlyUnresolvedSpecIds,
                                                  resolvedIdToSpecMap,
                                                  (callCount + 1));
            }
        }
    }

    private Map<String, TransformSpec> addResolvedTileSpecs(final StackId stackId,
                                                            final DBObject tileQuery,
                                                            final RenderParameters renderParameters) {
        final DBCollection tileCollection = getTileCollection(stackId);
        final DBCursor cursor = tileCollection.find(tileQuery);
        // order tile specs by tileId to ensure consistent coordinate mapping
        final DBObject orderBy = new BasicDBObject("tileId", 1);
        cursor.sort(orderBy);
        try {
            DBObject document;
            TileSpec tileSpec;
            while (cursor.hasNext()) {
                document = cursor.next();
                tileSpec = TileSpec.fromJson(document.toString());
                renderParameters.addTileSpec(tileSpec);
            }
        } finally {
            cursor.close();
        }

        LOG.debug("addResolvedTileSpecs: found {} tile spec(s) for {}.find({}).sort({})",
                  renderParameters.numberOfTileSpecs(), tileCollection.getFullName(), tileQuery, orderBy);

        return resolveTransformReferencesForTiles(stackId, renderParameters.getTileSpecs());
    }

    private DBObject lte(final double value) {
        return new BasicDBObject(QueryOperators.LTE, value);
    }

    private DBObject gte(final double value) {
        return new BasicDBObject(QueryOperators.GTE, value);
    }

    private BasicDBObject getIntersectsBoxQuery(final double z,
                                                final double x,
                                                final double y,
                                                final double lowerRightX,
                                                final double lowerRightY) {
        // intersection logic stolen from java.awt.Rectangle#intersects (without overflow checks)
        //   rx => minX, ry => minY, rw => maxX,        rh => maxY
        //   tx => x,    ty => y,    tw => lowerRightX, th => lowerRightY
        return new BasicDBObject("z", z).append(
                "minX", lte(lowerRightX)).append(
                "minY", lte(lowerRightY)).append(
                "maxX", gte(x)).append(
                "maxY", gte(y));
    }

    private void validateTransformReferences(final String context,
                                             final StackId stackId,
                                             final TransformSpec transformSpec) {

        final Set<String> unresolvedTransformSpecIds = transformSpec.getUnresolvedIds();

        if (unresolvedTransformSpecIds.size() > 0) {
            final DBCollection transformCollection = getTransformCollection(stackId);
            final List<TransformSpec> transformSpecList = getTransformSpecs(transformCollection,
                                                                            unresolvedTransformSpecIds);
            if (transformSpecList.size() != unresolvedTransformSpecIds.size()) {
                final Set<String> existingIds = new HashSet<>(transformSpecList.size());
                for (final TransformSpec existingTransformSpec : transformSpecList) {
                    existingIds.add(existingTransformSpec.getId());
                }
                final Set<String> missingIds = new TreeSet<>();
                for (final String id : unresolvedTransformSpecIds) {
                    if (! existingIds.contains(id)) {
                        missingIds.add(id);
                    }
                }
                throw new IllegalArgumentException(context + " references transform id(s) " + missingIds +
                                                   " which do not exist in the " +
                                                   transformCollection.getFullName() + " collection");
            }
        }
    }

    private Double getBound(final DBCollection tileCollection,
                            final DBObject tileQuery,
                            final String boundKey,
                            final boolean isMin) {

        Double bound = null;

        int order = -1;
        if (isMin) {
            order = 1;
        }

        final DBObject tileKeys = new BasicDBObject(boundKey, 1).append("_id", 0);
        final DBObject orderBy = new BasicDBObject(boundKey, order);

        final DBCursor cursor = tileCollection.find(tileQuery, tileKeys);
        cursor.sort(orderBy).limit(1);
        try {
            DBObject document;
            if (cursor.hasNext()) {
                document = cursor.next();
                bound = (Double) document.get(boundKey);
            }
        } finally {
            cursor.close();
        }

        LOG.debug("getBound: returning {} for {}.{}.find({},{}).sort({}).limit(1)",
                  bound, RENDER_DB_NAME, tileCollection.getName(), tileQuery, tileKeys, orderBy);

        return bound;
    }

    private DBCollection getStackCollection(final StackId stackId) {
        return renderDb.getCollection(stackId.getStackCollectionName());
    }

    private DBCollection getTileCollection(final StackId stackId) {
        return renderDb.getCollection(stackId.getTileCollectionName());
    }

    private DBCollection getTransformCollection(final StackId stackId) {
        return renderDb.getCollection(stackId.getTransformCollectionName());
    }

    private void validateRequiredParameter(final String context,
                                           final Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private void ensureTransformIndexes(final DBCollection transformCollection) {
        LOG.debug("ensureTransformIndexes: entry, {}", transformCollection.getName());
        transformCollection.createIndex(new BasicDBObject("id", 1),
                                        new BasicDBObject("unique", true).append("background", true));
        LOG.debug("ensureTransformIndexes: exit");
    }

    private void ensureTileIndexes(final DBCollection tileCollection) {
        LOG.debug("ensureTileIndexes: entry, {}", tileCollection.getName());
        final BasicDBObject background = new BasicDBObject("background", true);
        tileCollection.createIndex(new BasicDBObject("tileId", 1),
                                   new BasicDBObject("unique", true).append("background", true));
        tileCollection.createIndex(new BasicDBObject("z", 1), background);
        tileCollection.createIndex(new BasicDBObject("minX", 1), background);
        tileCollection.createIndex(new BasicDBObject("minY", 1), background);
        tileCollection.createIndex(new BasicDBObject("maxX", 1), background);
        tileCollection.createIndex(new BasicDBObject("maxY", 1), background);

        // compound index needed for layout file sorting
        tileCollection.createIndex(new BasicDBObject("z", 1).append("minY", 1).append("minX", 1), background);

        LOG.debug("ensureTileIndexes: exit");
    }

    private String getBulkResultMessage(final String context,
                                        final BulkWriteResult result,
                                        final int objectCount) {

        final StringBuilder message = new StringBuilder(128);

        message.append("processed ").append(objectCount).append(" ").append(context);

        if (result.isAcknowledged()) {
            final int updates = result.getMatchedCount();
            final int inserts = objectCount - updates;
            message.append(" with ").append(inserts).append(" inserts and ");
            message.append(updates).append(" updates");
        } else {
            message.append(" (result NOT acknowledged)");
        }

        return message.toString();
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDao.class);
}