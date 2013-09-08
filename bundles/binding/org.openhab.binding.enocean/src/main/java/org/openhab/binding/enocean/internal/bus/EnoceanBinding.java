/**
 * openHAB, the open Home Automation Bus.
 * Copyright (C) 2010-2012, openHAB.org <admin@openhab.org>
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with Eclipse (or a modified version of that library),
 * containing parts covered by the terms of the Eclipse Public License
 * (EPL), the licensors of this Program grant you additional permission
 * to convey the resulting work.
 */
package org.openhab.binding.enocean.internal.bus;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;

import java.lang.reflect.Constructor;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.enocean.java.ESP3Host;
import org.enocean.java.EnoceanSerialConnector;
import org.enocean.java.address.EnoceanParameterAddress;
import org.enocean.java.common.ParameterAddress;
import org.enocean.java.common.ParameterValueChangeListener;
import org.enocean.java.common.ProtocolConnector;
import org.enocean.java.common.values.Value;
import org.enocean.java.eep.EEPId;
import org.enocean.java.eep.RockerSwitch;
import org.enocean.java.eep.SingleInputContact;
import org.enocean.java.eep.TemperaturSensor;
import org.openhab.binding.enocean.EnoceanBindingProvider;
import org.openhab.binding.enocean.internal.converter.ButtonStateConverter;
import org.openhab.binding.enocean.internal.converter.CommandConverter;
import org.openhab.binding.enocean.internal.converter.ContactStateConverter;
import org.openhab.binding.enocean.internal.converter.ConverterFactory;
import org.openhab.binding.enocean.internal.converter.TemperatureNumberWithUnitConverter;
import org.openhab.binding.enocean.internal.profiles.DimmerOnOffProfile;
import org.openhab.binding.enocean.internal.profiles.Profile;
import org.openhab.binding.enocean.internal.profiles.RollershutterProfile;
import org.openhab.binding.enocean.internal.profiles.StandardProfile;
import org.openhab.core.binding.AbstractBinding;
import org.openhab.core.binding.BindingProvider;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.DimmerItem;
import org.openhab.core.library.items.RollershutterItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enocean binding implementation.
 * 
 * @author Thomas Letsch (contact@thomas-letsch.de)
 * @author Kai Kreuzer
 * @since 1.3.0
 */
public class EnoceanBinding extends AbstractBinding<EnoceanBindingProvider> implements ManagedService, ParameterValueChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(EnoceanBinding.class);

    private static final String CONFIG_KEY_SERIAL_PORT = "serialPort";

    private ConverterFactory converterFactory = new ConverterFactory();
    private Map<String, Profile> profiles = new HashMap<String, Profile>();

    protected EventPublisher eventPublisher = null;

    private String serialPort;

    private ProtocolConnector connector;

    private ESP3Host esp3Host;

    public EnoceanBinding() {
        converterFactory.addStateConverter(TemperaturSensor.PARAMETER_ID, DecimalType.class, TemperatureNumberWithUnitConverter.class);
        converterFactory.addStateConverter(RockerSwitch.BUTTON_I, OnOffType.class, ButtonStateConverter.class);
        converterFactory.addStateConverter(RockerSwitch.BUTTON_O, OnOffType.class, ButtonStateConverter.class);
        converterFactory.addStateConverter(SingleInputContact.PARAMETER_ID, OpenClosedType.class, ContactStateConverter.class);
    }

    @Override
    public void activate() {
        if (connector != null) {
            try {
                connector.connect(serialPort);
            } catch (Exception e) {
                logger.error("Could not connect to " + serialPort, e);
            }
        }
    }

    @Override
    public void deactivate() {
        connector.disconnect();
    }

    @Override
    protected void internalReceiveCommand(String itemName, Command command) {
        for (EnoceanBindingProvider provider : providers) {
            logger.debug("Checking provider with names {}", provider.getItemNames());
            ParameterAddress parameterAddress = provider.getParameterAddress(itemName);
            State actualState = provider.getItem(itemName).getState();
            String parameterKey = parameterAddress.getParameterId();
            CommandConverter<?, ?> commandConverter = converterFactory.getCommandConverter(parameterKey, command);
            if (commandConverter == null) {
                logger.debug("No command converter found for {}. No command will be executed.", parameterAddress);
                return;
            }
            State newState = commandConverter.convertFrom(actualState, command);
            setStateOnDevice(newState, parameterAddress);
        }
    }

    @Override
    protected void internalReceiveUpdate(String itemName, State newState) {
        for (EnoceanBindingProvider provider : providers) {
            logger.debug("Checking provider with names {}", provider.getItemNames());
            ParameterAddress parameterAddress = provider.getParameterAddress(itemName);
            setStateOnDevice(newState, parameterAddress);
        }
    }

    private void setStateOnDevice(State newState, ParameterAddress parameterAddress) {
        // TODO: Set new state on enocean device
    }

    @Override
    public void updated(Dictionary<String, ?> config) throws ConfigurationException {
        if (config == null) {
            return;
        }
        serialPort = (String) config.get(CONFIG_KEY_SERIAL_PORT);

        if (connector != null) {
            connector.disconnect();
        }
        try {
        	connect();
	    } catch(RuntimeException e) {
	    	if(e.getCause() instanceof NoSuchPortException) {
				StringBuilder sb = new StringBuilder("Available ports are:\n");
				Enumeration<?> portList = CommPortIdentifier.getPortIdentifiers();
				while (portList.hasMoreElements()) {
					CommPortIdentifier id = (CommPortIdentifier) portList.nextElement();
					if (id.getPortType() == CommPortIdentifier.PORT_SERIAL) {
						sb.append(id.getName() + "\n");
					}
				}
				sb.deleteCharAt(sb.length()-1);
				throw new ConfigurationException(CONFIG_KEY_SERIAL_PORT, "Serial port '" + serialPort + "' could not be opened. " + sb.toString());
	    	} else {
	    		throw e;
	    	}
	    }
    }

    @Override
    public void allBindingsChanged(BindingProvider provider) {
        if (provider instanceof EnoceanBindingProvider) {
            EnoceanBindingProvider enoceanBindingProvider = (EnoceanBindingProvider) provider;
            initializeAllItemsInProvider(enoceanBindingProvider);
        }
    }

    @Override
    public void bindingChanged(BindingProvider provider, String itemName) {
    	if(esp3Host!=null) {
	    	if (provider instanceof EnoceanBindingProvider) {
	            EnoceanBindingProvider enoceanBindingProvider = (EnoceanBindingProvider) provider;
	            processEEPs(enoceanBindingProvider, itemName);
	            queryAndSendActualState(enoceanBindingProvider, itemName);
	        }
    	}
    }

    private void initializeAllItemsInProvider(EnoceanBindingProvider provider) {
    	if(esp3Host!=null) {
	        logger.debug("Updating item state for items {}", provider.getItemNames());
	        for (String itemName : provider.getItemNames()) {
	            processEEPs(provider, itemName);
	            queryAndSendActualState(provider, itemName);
	        }
    	}
    }

    private void processEEPs(EnoceanBindingProvider enoceanBindingProvider, String itemName) {
    	EnoceanParameterAddress parameterAddress = enoceanBindingProvider.getParameterAddress(itemName);
        EEPId eep = enoceanBindingProvider.getEEP(itemName);
        esp3Host.addDeviceProfile(parameterAddress.getEnoceanDeviceId(), eep);
        Item item = enoceanBindingProvider.getItem(itemName);
        Class<Profile> customProfileClass = enoceanBindingProvider.getCustomProfile(itemName);
        if (customProfileClass != null) {
            Constructor<Profile> constructor;
            Profile profile;
            try {
                constructor = customProfileClass.getConstructor(Item.class, EventPublisher.class);
                profile = constructor.newInstance(item, eventPublisher);
                addProfile(item, parameterAddress, profile);
            } catch (Exception e) {
                logger.error("Could not create class for profile " + customProfileClass, e);
            }
        } else if (RockerSwitch.EEP_ID_1.equals(eep) || RockerSwitch.EEP_ID_2.equals(eep)) {
            if (item.getClass().equals(RollershutterItem.class)) {
                addRollershutterProfile(enoceanBindingProvider, itemName, parameterAddress);
            }
            if (item.getClass().equals(DimmerItem.class)) {
                addDimmerProfile(enoceanBindingProvider, itemName, parameterAddress);
            }
        }
    }

    private void addRollershutterProfile(EnoceanBindingProvider enoceanBindingProvider, String itemName,
            EnoceanParameterAddress parameterAddress) {
        RollershutterProfile profile = new RollershutterProfile(enoceanBindingProvider.getItem(itemName), eventPublisher);
        addProfile(enoceanBindingProvider.getItem(itemName), parameterAddress, profile);
    }

    private void addDimmerProfile(EnoceanBindingProvider enoceanBindingProvider, String itemName, EnoceanParameterAddress parameterAddress) {
        DimmerOnOffProfile profile = new DimmerOnOffProfile(enoceanBindingProvider.getItem(itemName), eventPublisher);
        addProfile(enoceanBindingProvider.getItem(itemName), parameterAddress, profile);
    }

    private void addProfile(Item item, ParameterAddress parameterAddress, Profile profile) {
        if (profiles.containsKey(parameterAddress.getAsString())) {
            profiles.get(parameterAddress.getAsString()).addItem(item);
        } else {
            profiles.put(parameterAddress.getAsString(), profile);
        }
    }

    private void queryAndSendActualState(EnoceanBindingProvider provider, String itemName) {
        EnoceanParameterAddress parameterAddress = provider.getParameterAddress(itemName);
        Item item = provider.getItem(itemName);
        if (item == null) {
            logger.warn("No item found for " + parameterAddress + " - doing nothing.");
            return;
        }
        State value = getValueFromDevice(parameterAddress, item);
        if (value != null) {
            eventPublisher.postUpdate(itemName, value);
        }
    }

    @Override
    public void setEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void unsetEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = null;
    }

    private Item getItemForParameter(ParameterAddress parameterAddress) {
        for (EnoceanBindingProvider provider : providers) {
            for (String itemName : provider.getItemNames()) {
                if (parameterAddress.equals(provider.getParameterAddress(itemName))) {
                    return provider.getItem(itemName);
                }
            }
        }
        return null;
    }

    private State getValueFromDevice(ParameterAddress parameterAddress, Item item) {
        // TODO: get value from enocean device. Not implemented yet.
        return null;
    }

    @Override
    public void valueChanged(ParameterAddress parameterAddress, Value valueObject) {
        logger.debug("Received new value {} for device at {}", valueObject, parameterAddress);
        Profile profile = null;
        if (profiles.containsKey(parameterAddress.getAsString())) {
            profile = profiles.get(parameterAddress.getAsString());
        } else if (profiles.containsKey(parameterAddress.getChannelAsString())) {
            profile = profiles.get(parameterAddress.getChannelAsString());
        } else if (profiles.containsKey(parameterAddress.getDeviceAsString())) {
            profile = profiles.get(parameterAddress.getDeviceAsString());
        } else {
            Item item = getItemForParameter(parameterAddress);
            profile = new StandardProfile(item, eventPublisher);
            addProfile(item, parameterAddress, profile);
        }
        profile.valueChanged(parameterAddress, valueObject);
    }

    /**
     * Connect to EnOcean controller through the java lib.
     */
    private void connect() {
        logger.info("Connecting to Enocean [serialPort='{}' ].", new Object[] { serialPort });

        connector = new EnoceanSerialConnector();
        connector.connect(serialPort);
        esp3Host = new ESP3Host(connector);
        esp3Host.addParameterChangeListener(this);
        for (EnoceanBindingProvider provider : providers) {
            initializeAllItemsInProvider(provider);
        }
        esp3Host.start();
    }

    /**
     * Used for testing purposes.
     * 
     * @param esp3Host
     */
    public void setEsp3Host(ESP3Host esp3Host) {
        this.esp3Host = esp3Host;
    }
}
