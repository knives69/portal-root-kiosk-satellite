package dev.portal.heyportal.binder;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public final class AudioServiceHelper {
    private static final String TAG = "HeyPortal_Binder";

    public static final String MGR_INTERFACE = "com.facebook.alohasdk.IAudioServiceManager";
    public static final String SVC_INTERFACE = "com.facebook.alohasdk.IAudioService";
    public static final String LST_INTERFACE = "com.facebook.alohasdk.IVoiceTriggerRecognitionMetadataListener";

    public static final int TRANSACTION_GET_AUDIO_SERVICE = 3;
    public static final int TRANSACTION_STOP_RECOGNITION = 11;
    public static final int TRANSACTION_START_RECOGNITION_WITH_META = 15;

    // Keep strong references so GC does not trigger binderDied() in audiomanager.
    private static IBinder sConnectionStub;
    private static IBinder sListenerStub;

    public interface DetectionCallback {
        void onDetected(int enrollId, String keyword, long beginTime, long endTime, float confidence, byte[] metadata);
    }

    public static IBinder getManagerService() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, "com.facebook.IAudioServiceManager");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get com.facebook.IAudioServiceManager from ServiceManager", t);
            return null;
        }
    }

    public static IBinder getAudioService(IBinder mgr) throws RemoteException {
        if (mgr == null) throw new IllegalArgumentException("Manager binder is null");
        if (sConnectionStub == null) {
            sConnectionStub = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel req, Parcel rep, int flags) throws RemoteException {
                    Log.i(TAG, "IAudioServiceConnection callback code=" + code);
                    return true;
                }
            };
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MGR_INTERFACE);
            data.writeStrongBinder(sConnectionStub);
            boolean ok = mgr.transact(TRANSACTION_GET_AUDIO_SERVICE, data, reply, 0);
            if (!ok) {
                throw new RemoteException("transact(getAudioService) returned false");
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static int startRecognitionWithMetadata(IBinder svc, String keyword, DetectionCallback callback) throws RemoteException {
        return startRecognitionWithMetadata(svc, Collections.singletonList(keyword), callback);
    }

    public static int startRecognitionWithMetadata(IBinder svc, List<String> keywords, DetectionCallback callback) throws RemoteException {
        if (svc == null) throw new IllegalArgumentException("AudioService binder is null");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC_INTERFACE);
            data.writeStringList(keywords);

            sListenerStub = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel req, Parcel rep, int flags) throws RemoteException {
                    if (code == 1) { // onDetected
                        req.enforceInterface(LST_INTERFACE);
                        int id = req.readInt();
                        String kw = req.readString();
                        long begin = req.readLong();
                        long end = req.readLong();
                        float conf = req.readFloat();
                        byte[] meta = req.createByteArray();
                        if (rep != null) rep.writeNoException();

                        Log.i(TAG, "DSP DETECTED: id=" + id + " kw=" + kw + " conf=" + conf + " [" + begin + ".." + end + "]");
                        if (callback != null) {
                            callback.onDetected(id, kw, begin, end, conf, meta);
                        }
                        return true;
                    }
                    return super.onTransact(code, req, rep, flags);
                }
            };

            data.writeStrongBinder(sListenerStub);
            boolean ok = svc.transact(TRANSACTION_START_RECOGNITION_WITH_META, data, reply, 0);
            if (!ok) {
                throw new RemoteException("transact(startRecognitionWithMetadata) returned false");
            }
            reply.readException();
            int enrollId = reply.readInt();
            Log.i(TAG, "startRecognitionWithMetadata SUCCESS! enrollId=" + enrollId + " kw=" + keywords);
            return enrollId;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static int stopRecognition(IBinder svc, int enrollId) throws RemoteException {
        if (svc == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC_INTERFACE);
            data.writeInt(enrollId);
            boolean ok = svc.transact(TRANSACTION_STOP_RECOGNITION, data, reply, 0);
            if (!ok) {
                throw new RemoteException("transact(stopRecognition) returned false");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
