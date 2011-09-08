/**
 * Copyright (C) 2010 Regis Montoya (aka r3gis - www.r3gis.fr)
 * This file is part of CSipSimple.
 *
 *  CSipSimple is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CSipSimple is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with CSipSimple.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.csipsimple.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pjsip.pjsua.pjsua;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;

import com.csipsimple.service.SipService;
import com.csipsimple.service.SipWakeLock;

public class TimerWrapper extends BroadcastReceiver {
	
	private static final String THIS_FILE = "Timer wrap";
	
	private static final String TIMER_ACTION = "com.csipsimple.PJ_TIMER";
	private static final String EXTRA_TIMER_SCHEME = "timer";
	private SipService service;
	private AlarmManager alarmManager;
	private SipWakeLock wakeLock;

	//private WakeLock wakeLock;
	private static TimerWrapper singleton;
	
	Map<Integer, PendingIntent> pendings = new HashMap<Integer, PendingIntent>();
	
	private TimerWrapper(SipService ctxt) {
		service = ctxt;
		alarmManager = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
		IntentFilter filter = new IntentFilter(TIMER_ACTION);
		filter.addDataScheme(EXTRA_TIMER_SCHEME);
		service.registerReceiver(this, filter);
		
		wakeLock = new SipWakeLock((PowerManager) ctxt.getSystemService(Context.POWER_SERVICE));
	}
	
	
	synchronized private void quit() {
		Log.v(THIS_FILE, "Quit this wrapper");
		try {
			service.unregisterReceiver(this);
		} catch (IllegalArgumentException e) {
			Log.e(THIS_FILE, "Impossible to destroy timer wrapper", e);
		}
		
		List<Integer> keys = getPendingsKeys();
		//Log.v(THIS_FILE, "In buffer : "+keys);
		for(Integer key : keys) {
			int heapId = (key & 0xFFF000) >> 8;
			int timerId = key & 0x000FFF;
			doCancel(heapId, timerId);
		}
		
		wakeLock.reset();
	}
	
	private synchronized List<Integer> getPendingsKeys(){
		ArrayList<Integer> keys = new ArrayList<Integer>();
		for(Integer key : pendings.keySet()) {
			keys.add(key);
		}
		return keys;
	}

	private PendingIntent getPendingIntentForTimer(int heapId, int timerId) {
		Intent intent = new Intent(TIMER_ACTION);
		String toSend = EXTRA_TIMER_SCHEME+"://"+Integer.toString(heapId)+"/"+Integer.toString(timerId);
		intent.setData(Uri.parse(toSend));
		return PendingIntent.getBroadcast(service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
	}
	
	private int getHashPending(int heapId, int timerId) {
		return heapId << 16 | timerId;
	}
	
	
	synchronized private int doSchedule(int heapId, int timerId, int intervalMs) {
		PendingIntent pendingIntent = getPendingIntentForTimer(heapId, timerId);
		
		//List<Integer> keys = getPendingsKeys();
		//Log.v(THIS_FILE, "1 In buffer : "+keys);
		pendings.put(getHashPending(heapId, timerId), pendingIntent);
		
		long firstTime = SystemClock.elapsedRealtime() + intervalMs;
		Log.v(THIS_FILE, "Ask to schedule timer : "+timerId );
		
		int type = AlarmManager.ELAPSED_REALTIME_WAKEUP;
		if(intervalMs < 1000) {
			// If less than 1 sec, do not wake up -- that's probably stun check so useless to wake up about that
			type = AlarmManager.ELAPSED_REALTIME;
		}
		alarmManager.set(type, firstTime, pendingIntent);
		
		return 0;
	}
	
	synchronized private int doCancel(int heapId, int timerId) {
		int hash = getHashPending(heapId, timerId);
		PendingIntent pendingIntent = pendings.get(hash);
		Log.v(THIS_FILE, "Cancel timer : "+ timerId);
		if(pendingIntent != null) {
			pendings.remove(hash);
		}else {
			Log.w(THIS_FILE, "Not found timer to cancel "+hash);
			pendingIntent = getPendingIntentForTimer(heapId, timerId);
		}
		alarmManager.cancel(pendingIntent);
		return 0;
	}
	
	
	
	@Override
	public void onReceive(Context context, Intent intent) {
		
		if(TIMER_ACTION.equalsIgnoreCase(intent.getAction())) {
			
			final int heapId = Integer.parseInt(intent.getData().getAuthority());
			final int timerId = Integer.parseInt(intent.getData().getLastPathSegment());
			synchronized(TimerWrapper.this){
				if(!pendings.containsKey(getHashPending(heapId, timerId))) {
					Log.w(THIS_FILE, "Fireing a cancelled timer - abort");
					return;
				}
			}
			Log.v(THIS_FILE, "Fire timer : "+ timerId);
			TimerJob t = new TimerJob(heapId, timerId);
			t.start();
			// TODO : ensure a running job is not ongoing for another timer when enqueuing a new job
		}
	}
	
	
	
	
	// Public API
	public static void create(SipService ctxt) {
		singleton = new TimerWrapper(ctxt);
	}
	
	public static void destroy() {
		if(singleton != null) {
			singleton.quit();
		}
	}
	
	
	public static int schedule(int heapId, int timerId, int time) {
		if(singleton == null) {
			Log.e(THIS_FILE, "Timer NOT initialized");
			return -1;
		}
		singleton.doSchedule(heapId, timerId, time);
			
		
		return 0;
	}
	
	public static int cancel(int heapId, int timerId) {
		singleton.doCancel(heapId, timerId);
		
		return 0;
	}
	
	

	private class TimerJob extends Thread {
		private int heapId;
		private int timerId;
		private int hash;

		public TimerJob(int aHeapId, int aTimerId) {
			heapId = aHeapId;
			timerId = aTimerId;
			hash = getHashPending(heapId, timerId);
			wakeLock.acquire(this);
		}

		@Override
		public void run() {
			synchronized(TimerWrapper.this){
				if(!pendings.containsKey(hash)) {
					Log.w(THIS_FILE, "Running a cancelled timer - abort");
					wakeLock.release(this);
					return;
				}
			}
			
			try {
				pjsua.pj_timer_fire(heapId, timerId);
			}catch(Exception e) {
				Log.e(THIS_FILE, "Native error ", e);
			}finally {
				wakeLock.release(this);
				
				synchronized(TimerWrapper.this){
					pendings.remove(hash);
				}
				//List<Integer> keys = getPendingsKeys();
				//Log.v(THIS_FILE, "In buffer : "+keys);
			}
		}
	}


}