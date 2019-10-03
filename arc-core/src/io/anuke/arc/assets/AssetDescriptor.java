package io.anuke.arc.assets;

import io.anuke.arc.files.*;
import io.anuke.arc.util.reflect.*;

/**
 * Describes an asset to be loaded by its filename, type and {@link AssetLoaderParameters}. Instances of this are used in
 * {@link AssetLoadingTask} to load the actual asset.
 * @author mzechner
 */
public class AssetDescriptor<T>{
    public final String fileName;
    public final Class<T> type;
    public final AssetLoaderParameters params;
    /** The resolved file. May be null if the fileName has not been resolved yet. */
    public FileHandle file;
    /** Callback for when this asset is loaded.*/
    //public Consumer<T> loaded = t -> {};

    public AssetDescriptor(Class<T> assetType){
        this(ClassReflection.getSimpleName(assetType), assetType, null);
    }

    public AssetDescriptor(String fileName, Class<T> assetType){
        this(fileName, assetType, null);
    }

    /** Creates an AssetDescriptor with an already resolved name. */
    public AssetDescriptor(FileHandle file, Class<T> assetType){
        this(file, assetType, null);
    }

    public AssetDescriptor(String fileName, Class<T> assetType, AssetLoaderParameters<T> params){
        this.fileName = fileName.replaceAll("\\\\", "/");
        this.type = assetType;
        this.params = params;
    }

    /** Creates an AssetDescriptor with an already resolved name. */
    public AssetDescriptor(FileHandle file, Class<T> assetType, AssetLoaderParameters<T> params){
        this.fileName = file.path().replaceAll("\\\\", "/");
        this.file = file;
        this.type = assetType;
        this.params = params;
    }

    @Override
    public String toString(){
        return fileName +
        ", " +
        type.getName();
    }
}
