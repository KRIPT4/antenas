package ar.com.lichtmaier.antenas;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.SphericalUtil;

import org.gavaghan.geodesy.GlobalCoordinates;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AntenasRepository
{
	private final Context context;

	private static final int PRECISIÓN_ACEPTABLE = 150;

	static class AntenaListada
	{
		final Antena antena;
		double distancia;
		final boolean lejos;

		AntenaListada(Antena antena, double distancia, boolean lejos)
		{
			this.antena = antena;
			this.distancia = distancia;
			this.lejos = lejos;
		}

		@Override
		public String toString()
		{
			StringBuilder sb = new StringBuilder();
			sb.append('{').append(antena).append(' ').append((int)distancia).append('m');
			if(lejos)
				sb.append(" lejos");
			sb.append('}');
			return sb.toString();
		}
	}

	AntenasRepository(Context context)
	{
		this.context = context.getApplicationContext();
	}

	LiveData<List<AntenaListada>> dameAntenasAlrededor(LiveData<Location> locationLiveData)
	{
		return new AntenasAlrededorLiveData(locationLiveData);
	}

	private class AntenasAlrededorLiveData extends MediatorLiveData<List<AntenaListada>> implements SharedPreferences.OnSharedPreferenceChangeListener
	{
		private final SharedPreferences prefs;
		private LiveData<List<Antena>> ldac;
		private Location location;

		final private Map<Antena, Boolean> cachéCercaníaAntena = new HashMap<>();
		private LatLng posCachéCercanía;

		AntenasAlrededorLiveData(LiveData<Location> locationLiveData)
		{
			location = locationLiveData.getValue();
			prefs = PreferenceManager.getDefaultSharedPreferences(context);
			addSource(locationLiveData, (loc) -> {
				if(loc == null)
					return;
				location = loc;
				process();
			});
		}

		@Override
		protected void onActive()
		{
			super.onActive();
			prefs.registerOnSharedPreferenceChangeListener(this);
		}

		@Override
		protected void onInactive()
		{
			super.onInactive();
			prefs.unregisterOnSharedPreferenceChangeListener(this);
		}

		private void process()
		{
			if(location == null)
				return;
			int maxDist = Integer.parseInt(prefs.getString("max_dist", "60")) * 1000;
			if(ldac != null)
				removeSource(ldac);
			GlobalCoordinates gcoords = new GlobalCoordinates(location.getLatitude(), location.getLongitude());
			ldac = Antena.dameAntenasCercaLD(context, gcoords, maxDist, prefs.getBoolean("menos", true));

			addSource(ldac, antenasAlrededor -> {
				CachéDeContornos cdc = CachéDeContornos.dameInstancia(context);
				try
				{
					if(antenasAlrededor == null)
						return;

					LatLng coords = new LatLng(location.getLatitude(), location.getLongitude());

					boolean renovarCaché = false;
					if(posCachéCercanía != null)
					{
						if(SphericalUtil.computeDistanceBetween(posCachéCercanía, coords) > 200)
						{
							if(Log.isLoggable("antenas", Log.DEBUG))
								Log.d("antenas", "Renuevo caché de cercanía de " + cachéCercaníaAntena.size() + " elementos.");
							renovarCaché = true;
							posCachéCercanía = coords;
							cachéCercaníaAntena.keySet().retainAll(antenasAlrededor);
						}
					} else
					{
						posCachéCercanía = coords;
					}

					boolean noUsarContornos = !prefs.getBoolean("usar_contornos", true);
					List<AntenaListada> res = new ArrayList<>();
					for(Antena a : antenasAlrededor)
					{
						Boolean cerca = a.país != País.US || noUsarContornos ? Boolean.TRUE : cachéCercaníaAntena.get(a);
						res.add(new AntenaListada(a, a.distanceTo(gcoords), cerca != null && !cerca));
						if(renovarCaché || cerca == null)
						{
							LiveData<Boolean> ec = cdc.enContorno(a, coords);
							addSource(ec, enContorno -> {
								removeSource(ec);
								cachéCercaníaAntena.put(a, enContorno);
								processDemorado();
							});
						}
					}
					setValue(res);
				} finally
				{
					cdc.devolver();
				}
			});
		}

		final private Handler handler = new LlamarAProcesar(this);

		void processDemorado()
		{
			if(!handler.hasMessages(1))
				handler.sendEmptyMessageDelayed(1, 1000);
		}

		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
		{
			if(key.equals("max_dist") || key.equals("menos") || key.equals("usar_contornos"))
				process();
		}
	}

	private static class LlamarAProcesar extends Handler
	{
		private final WeakReference<AntenasAlrededorLiveData> ref;

		private LlamarAProcesar(AntenasAlrededorLiveData ld)
		{
			ref = new WeakReference<>(ld);
		}

		@Override
		public void handleMessage(Message msg)
		{
			AntenasAlrededorLiveData ld = ref.get();
			if(ld != null)
				ld.process();
		}
	}
}