package org.jsmpp.session;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Hashtable;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jsmpp.BindType;
import org.jsmpp.DefaultPDUReader;
import org.jsmpp.DefaultPDUSender;
import org.jsmpp.InterfaceVersion;
import org.jsmpp.InvalidCommandLengthException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.NumberingPlanIndicator;
import org.jsmpp.PDUReader;
import org.jsmpp.PDUSender;
import org.jsmpp.PDUStringException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.SynchronizedPDUSender;
import org.jsmpp.TypeOfNumber;
import org.jsmpp.bean.BindResp;
import org.jsmpp.bean.Command;
import org.jsmpp.bean.DataCoding;
import org.jsmpp.bean.DataSmResp;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.EnquireLinkResp;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.QuerySmResp;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.SubmitSmResp;
import org.jsmpp.bean.UnbindResp;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.PendingResponse;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.extra.SessionState;
import org.jsmpp.session.connection.Connection;
import org.jsmpp.session.connection.ConnectionFactory;
import org.jsmpp.session.connection.socket.SocketConnectionFactory;
import org.jsmpp.util.DefaultComposer;
import org.jsmpp.util.IntUtil;
import org.jsmpp.util.Sequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author uudashr
 * @version 2.0
 *
 */
public class SMPPSession {
	private static final Logger logger = LoggerFactory.getLogger(SMPPSession.class);
	private static final Random random = new Random();

	/* Utility */
	private final PDUSender pduSender;
    private final PDUReader pduReader;
	
    /* Connection */
	private final ConnectionFactory connFactory;
	private Connection conn;
	private DataInputStream in;
	private OutputStream out;
	
	private final Sequence sequence = new Sequence(1);
	private final ResponseHandler responseHandler = new ResponseHandlerImpl();
	private final Hashtable<Integer, PendingResponse<? extends Command>> pendingResponse = new Hashtable<Integer, PendingResponse<? extends Command>>();
	private int sessionTimer = 5000;
	private long transactionTimer = 2000;
	private MessageReceiverListener messageReceiverListener;
    private SessionStateListenerDecorator sessionStateListener = new SessionStateListenerDecorator();
    private SMPPSessionContext sessionContext = new SMPPSessionContext(this, sessionStateListener);
	private EnquireLinkSender enquireLinkSender;
	private String sessionId = newSessionId();
	private int pduDispatcherThreadCount = 3;
	
	public SMPPSession() {
        this(new SynchronizedPDUSender(new DefaultPDUSender(new DefaultComposer())), 
                new DefaultPDUReader(), 
                SocketConnectionFactory.getInstance());
    }
	
	public SMPPSession(PDUSender pduSender, PDUReader pduReader, 
	        ConnectionFactory connFactory) {
	    this.pduSender = pduSender;
	    this.pduReader = pduReader;
	    this.connFactory = connFactory;
	    
    }
	
	public SMPPSession(String host, int port, BindParameter bindParam,
            PDUSender pduSender, PDUReader pduReader,
            ConnectionFactory connFactory) throws IOException {
	    this(pduSender, pduReader, connFactory);
	    connectAndBind(host, port, bindParam);
	}
	
	public SMPPSession(String host, int port, BindParameter bindParam) throws IOException {
        this();
        connectAndBind(host, port, bindParam);
    }
	
	/**
	 * Open connection and bind immediately.
	 * 
	 * @param host is the SMSC host address.
	 * @param port is the SMSC listen port.
	 * @param bindType is the bind type.
	 * @param systemId is the system id.
	 * @param password is the password.
	 * @param systemType is the system type.
	 * @param addrTon is the address TON.
	 * @param addrNpi is the address NPI.
	 * @param addressRange is the address range.
	 * @throws IOException if there is an IO error found.
	 */
	public void connectAndBind(String host, int port, BindType bindType,
            String systemId, String password, String systemType,
            TypeOfNumber addrTon, NumberingPlanIndicator addrNpi,
            String addressRange) throws IOException {
	    connectAndBind(host, port, new BindParameter(bindType, systemId,
                password, systemType, addrTon, addrNpi, addressRange));
	}
	
	/**
	 * Open connection and bind immediately.
	 * 
	 * @param host is the SMSC host address.
	 * @param port is the SMSC listen port.
	 * @param bindParam is the bind parameters.
	 * @return the SMSC system id.
	 * @throws IOException if there is an IO error found.
	 */
	public String connectAndBind(String host, int port, BindParameter bindParam) 
	        throws IOException {
	    logger.debug("Connect and bind to " + host + " port " + port);
		if (sequence.currentValue() != 1) {
			throw new IOException("Failed connecting");
		}
		
		conn = connFactory.createConnection(host, port);
		logger.info("Connected");
		
		sessionContext.open();
		try {
			in = new DataInputStream(conn.getInputStream());
			out = conn.getOutputStream();
			
			new PDUReaderWorker().start();
			String smscSystemId = sendBind(bindParam.getBindType(), bindParam.getSystemId(), bindParam.getPassword(), bindParam.getSystemType(),
                    InterfaceVersion.IF_34, bindParam.getAddrTon(), bindParam.getAddrNpi(), bindParam.getAddressRange());
			sessionContext.bound(bindParam.getBindType());
			
			enquireLinkSender = new EnquireLinkSender();
			enquireLinkSender.start();
			return smscSystemId;
		} catch (PDUStringException e) {
		    logger.error("Failed sending bind command", e);
		    throw new IOException("Failed sending bind since some string parameter area invalid : " + e.getMessage());
		} catch (NegativeResponseException e) {
			String message = "Receive negative bind response";
			logger.error(message, e);
			close();
			throw new IOException(message + ": " + e.getMessage());
		} catch (InvalidResponseException e) {
			String message = "Receive invalid response of bind";
			logger.error(message, e);
			close();
			throw new IOException(message + ": " + e.getMessage());
		} catch (ResponseTimeoutException e) {
			String message = "Waiting bind response take time to long";
			logger.error(message, e);
			close();
			throw new IOException(message + ": " + e.getMessage());
		} catch (IOException e) {
			logger.error("IO Error occur", e);
			close();
			throw e;
		}
	}
	
	/**
	 * Sending bind with 1 minutes timeout.
	 *  
	 * @param bindType is the bind type.
     * @param systemId is the system id.
     * @param password is the password.
     * @param systemTypeis the system type.
     * @param interfaceVersion is the interface version.
     * @param addrTon is the address TON.
     * @param addrNpi is the address NPI.
     * @param addressRange is the address range.
     * @return SMSC system id.
     * @throws PDUStringException if we enter invalid bind parameter(s).
     * @throws ResponseTimeoutException if there is no valid response after defined millisecond.
     * @throws InvalidResponseException if there is invalid response found.
     * @throws NegativeResponseException if we receive negative response.
     * @throws IOException if there is an IO error occur.
	 */
	private String sendBind(BindType bindType, String systemId,
            String password, String systemType,
            InterfaceVersion interfaceVersion, TypeOfNumber addrTon,
            NumberingPlanIndicator addrNpi, String addressRange)
            throws PDUStringException, ResponseTimeoutException,
            InvalidResponseException, NegativeResponseException, IOException {
	    return sendBind(bindType, systemId, password, systemType, interfaceVersion, addrTon, addrNpi, addressRange, 60000);
	}
	
	/**
	 * Sending bind.
	 * 
	 * @param bindType is the bind type.
	 * @param systemId is the system id.
	 * @param password is the password.
	 * @param systemTypeis the system type.
	 * @param interfaceVersion is the interface version.
	 * @param addrTon is the address TON.
	 * @param addrNpi is the address NPI.
	 * @param addressRange is the address range.
	 * @param timeout is the max time waiting for bind response. 
	 * @return SMSC system id.
	 * @throws PDUStringException if we enter invalid bind parameter(s).
	 * @throws ResponseTimeoutException if there is no valid response after defined millisecond.
	 * @throws InvalidResponseException if there is invalid response found.
	 * @throws NegativeResponseException if we receive negative response.
	 * @throws IOException if there is an IO error occur.
	 */
	private String sendBind(BindType bindType, String systemId,
			String password, String systemType,
			InterfaceVersion interfaceVersion, TypeOfNumber addrTon,
			NumberingPlanIndicator addrNpi, String addressRange, long timeout)
			throws PDUStringException, ResponseTimeoutException,
			InvalidResponseException, NegativeResponseException, IOException {
		int seqNum = sequence.nextValue();
		PendingResponse<BindResp> pendingResp = new PendingResponse<BindResp>(timeout);
		pendingResponse.put(seqNum, pendingResp);
		
		try {
			pduSender.sendBind(out, bindType, seqNum, systemId, password, systemType, interfaceVersion, addrTon, addrNpi, addressRange);
		} catch (IOException e) {
			logger.error("Failed sending bind command", e);
			pendingResponse.remove(seqNum);
			throw e;
		}
		
		try {
			pendingResp.waitDone();
			logger.info("Bind response received");
		} catch (ResponseTimeoutException e) {
			pendingResponse.remove(seqNum);
			throw e;
		} catch (InvalidResponseException e) {
			pendingResponse.remove(seqNum);
			throw e;
		}
		
		if (pendingResp.getResponse().getCommandStatus() != SMPPConstant.STAT_ESME_ROK) {
			throw new NegativeResponseException(pendingResp.getResponse().getCommandStatus());
		}
		
		return pendingResp.getResponse().getSystemId();
	}
	
	
	/**
	 * Submit short message with specified parameter.
	 * 
     * @param serviceType is the service_type parameter.
     * @param sourceAddrTon is the source_addr_ton parameter.
     * @param sourceAddrNpi is the source_addr_npi parameter.
     * @param sourceAddr is the source_addr parameter.
     * @param destAddrTon is the dest_addr_ton parameter.
     * @param destAddrNpi is the dest_addr_npi parameter.
     * @param destinationAddr is the destination_addr parameter.
     * @param esmClass is the esm_class parameter.
     * @param protocolId is the protocol_id parameter.
     * @param priorityFlag is the priority_flag parameter.
     * @param scheduleDeliveryTime is the schedule_delivery_time parameter. (It is time in string format).
     * @param validityPeriod is the validity_period parameter (It is time in string format).
     * @param registeredDelivery is the registered_delivery parameter.
     * @param replaceIfPresentFlag is the replace_if_present_flag parameter
     * @param dataCoding is the data_coding parameter.
     * @param smDefaultMsgId is the sm_default_message_id parameter.
     * @param shortMessage is the short message.
     * @param optionalParameters is the optional parameters.
     * @return the message id from SMSC.
     * @throws PDUStringException if we enter invalid bind parameter(s).
     * @throws ResponseTimeoutException if there is no valid response after defined millisecond.
     * @throws InvalidResponseException if there is invalid response found.
     * @throws NegativeResponseException if we receive negative response.
     * @throws IOException if there is an IO error found.
     */
    public String submitShortMessage(String serviceType,
            TypeOfNumber sourceAddrTon, NumberingPlanIndicator sourceAddrNpi,
            String sourceAddr, TypeOfNumber destAddrTon,
            NumberingPlanIndicator destAddrNpi, String destinationAddr,
            ESMClass esmClass, byte protocolId, byte priorityFlag,
            String scheduleDeliveryTime, String validityPeriod,
            RegisteredDelivery registeredDelivery, byte replaceIfPresentFlag,
            DataCoding dataCoding, byte smDefaultMsgId, byte[] shortMessage,
            OptionalParameter... optionalParameters) throws PDUStringException,
            ResponseTimeoutException, InvalidResponseException,
            NegativeResponseException, IOException {
    	
        SendSubmitSmCommandTask submitSmTask = new SendSubmitSmCommandTask(out,
                pduSender, serviceType, sourceAddrTon, sourceAddrNpi,
                sourceAddr, destAddrTon, destAddrNpi, destinationAddr,
                esmClass, protocolId, priorityFlag, scheduleDeliveryTime,
                validityPeriod, registeredDelivery, replaceIfPresentFlag,
                dataCoding, smDefaultMsgId, shortMessage, optionalParameters);
    	
        SubmitSmResp resp = (SubmitSmResp)sendSmCommand(submitSmTask);
    	validateResponse(resp);
    	
    	return resp.getMessageId();
    }
    
    /**
     * Send the data_sm command.
     * 
     * @param serviceType is the service_type parameter.
     * @param sourceAddrTon is the source_addr_ton parameter.
     * @param sourceAddrNpi is the source_addr_npi parameter.
     * @param sourceAddr is the source_addr parameter.
     * @param destAddrTon is the dest_addr_ton parameter.
     * @param destAddrNpi is the dest_addr_npi parameter.
     * @param destinationAddr is the destination_addr parameter.
     * @param esmClass is the esm_class parameter.
     * @param registeredDelivery is the registered_delivery parameter.
     * @param dataCoding is the data_coding parameter.
     * @param optionalParameters is the optional parameters.
     * @return the result of data_sm (data_sm_resp).
     * @throws PDUStringException if there is an invalid PDU String found.
     * @throws ResponseTimeoutException if the response take time too long.
     * @throws InvalidResponseException if the response is invalid.
     * @throws NegativeResponseException if the response return non-ok command_status.
     * @throws IOException if there is an IO error found.
     */
    public DataSmResult dataShortMessage(String serviceType,
            TypeOfNumber sourceAddrTon, NumberingPlanIndicator sourceAddrNpi,
            String sourceAddr, TypeOfNumber destAddrTon,
            NumberingPlanIndicator destAddrNpi, String destinationAddr,
            ESMClass esmClass, RegisteredDelivery registeredDelivery,
            DataCoding dataCoding, OptionalParameter... optionalParameters)
            throws PDUStringException, ResponseTimeoutException,
            InvalidResponseException, NegativeResponseException, IOException {
        
        
        SendDataSmCommandTask task = new SendDataSmCommandTask(out, pduSender, 
                serviceType, sourceAddrTon, sourceAddrNpi, sourceAddr, destAddrTon, 
                destAddrNpi, destinationAddr, esmClass, registeredDelivery, 
                dataCoding, optionalParameters);
        
        DataSmResp resp = (DataSmResp)sendSmCommand(task);
        
        return new DataSmResult(resp.getMessageId(), resp.getOptionalParameters());
    }
    
    /**
     * Query short message.
     * 
     * @param messageId is the message_id parameter.
     * @param sourceAddrTon is the source_addr_ton parameter.
     * @param sourceAddrNpi is the source_addr_npi parameter.
     * @param sourceAddr is the source_addr parameter.
     * @return the query_sm response (query_sm_resp).
     * @throws PDUStringException if there is an invalid PDU String found.
     * @throws ResponseTimeoutException if the response take time too long.
     * @throws InvalidResponseException if the response is invalid.
     * @throws NegativeResponseException if the response contains non-ok command_status.
     * @throws IOException if there is an IO error found.
     */
    public QuerySmResult queryShortMessage(String messageId, TypeOfNumber sourceAddrTon,
    		NumberingPlanIndicator sourceAddrNpi, String sourceAddr)
    		throws PDUStringException, ResponseTimeoutException,
    		InvalidResponseException, NegativeResponseException, IOException {
    	
        QuerySmCommandTask task = new QuerySmCommandTask(out, pduSender, messageId, 
                sourceAddrTon, sourceAddrNpi, sourceAddr);
        
        
    	QuerySmResp resp = (QuerySmResp)sendSmCommand(task);
    	
    	if (resp.getMessageId().equals(messageId)) {
    		return new QuerySmResult(resp.getFinalDate(), resp.getMessageState(), resp.getErrorCode());
    	} else {
    		// message id requested not same as the returned
    		throw new InvalidResponseException("Requested message_id doesn't match with the result");
    	}
    }
    
    /**
     * Send common short message command task.
     * 
     * @param task is the task.
     * @return the command response.
     * @throws PDUStringException if there is invalid PDU String found.
     * @throws ResponseTimeoutException if the response has reach it timeout.
     * @throws InvalidResponseException if invalid response found.
     * @throws NegativeResponseException if the negative response found.
     * @throws IOException if there is an IO error found.
     */
    public Command sendSmCommand(SendSmCommandTask task)
            throws PDUStringException, ResponseTimeoutException,
            InvalidResponseException, NegativeResponseException, IOException {
        
        int seqNum = sequence.nextValue();
        PendingResponse<Command> pendingResp = new PendingResponse<Command>(transactionTimer);
        pendingResponse.put(seqNum, pendingResp);
        try {
            task.executeTask(out, seqNum);
        } catch (IOException e) {
            logger.error("Failed sending " + task.getSmName() + " command", e);
            pendingResponse.remove(seqNum);
            close();
            throw e;
        }
        
        try {
            pendingResp.waitDone();
            logger.debug(task.getSmName() + " response received");
        } catch (ResponseTimeoutException e) {
            pendingResponse.remove(seqNum);
            logger.debug("Response timeout for " + task.getSmName() + " with sessionIdSequence number " + seqNum);
            throw e;
        } catch (InvalidResponseException e) {
            pendingResponse.remove(seqNum);
            throw e;
        }
        
        Command resp = pendingResp.getResponse();
        validateResponse(resp);
        return resp;
        
    }
    
    /**
     * Validate the response, the command_status should be 0 otherwise will
     * throw {@link NegativeResponseException}.
     * 
     * @param response is the response.
     * @throws NegativeResponseException if the command_status value is not zero.
     */
    private static void validateResponse(Command response) throws NegativeResponseException {
        if (response.getCommandStatus() != SMPPConstant.STAT_ESME_ROK) {
            throw new NegativeResponseException(response.getCommandStatus());
        }
    }

    /**
     * Get the session id of session.
     * 
     * @return the session id.
     */
    public String getSessionId() {
    	return sessionId;
    }

    private void unbind() throws ResponseTimeoutException, InvalidResponseException, IOException {
		int seqNum = sequence.nextValue();
		PendingResponse<UnbindResp> pendingResp = new PendingResponse<UnbindResp>(transactionTimer);
		pendingResponse.put(seqNum, pendingResp);
		
		try {
			pduSender.sendUnbind(out, seqNum);
		} catch (IOException e) {
			logger.error("Failed sending unbind", e);
			pendingResponse.remove(seqNum);
			throw e;
		}
		
		try {
			pendingResp.waitDone();
			logger.info("Unbind response received");
			sessionContext.unbound();
		} catch (ResponseTimeoutException e) {
			pendingResponse.remove(seqNum);
			throw e;
		} catch (InvalidResponseException e) {
			pendingResponse.remove(seqNum);
			throw e;
		}
		
		if (pendingResp.getResponse().getCommandStatus() != SMPPConstant.STAT_ESME_ROK)
			logger.warn("Receive NON-OK response of unbind");
	}

	public int getSessionTimer() {
		return sessionTimer;
	}
	
	public void setSessionTimer(int sessionTimer) {
		this.sessionTimer = sessionTimer;
		if (sessionContext.getSessionState().isBound()) {
			try {
				conn.setSoTimeout(sessionTimer);
			} catch (IOException e) {
				logger.error("Failed setting so_timeout for session timer", e);
			}
		}
	}
	
	public long getTransactionTimer() {
		return transactionTimer;
	}
	
	public void setTransactionTimer(long transactionTimer) {
		this.transactionTimer = transactionTimer;
	}
	
	public void setPduDispatcherThreadCount(int pduDispatcherThreadCount) throws IllegalStateException {
	    if (!sessionContext.getSessionState().equals(SessionState.CLOSED)) {
	        throw new IllegalStateException(
                    "Cannot set pdu distpatcher thread count since the pdu dispatcher thread already created.");
	    }
        this.pduDispatcherThreadCount = pduDispatcherThreadCount;
    }
	
	public int getPduDispatcherThreadCount() {
        return pduDispatcherThreadCount;
    }
	
	public SessionState getSessionState() {
		return sessionContext.getSessionState();
	}
	
	public SessionStateListener getSessionStateListener() {
        return sessionStateListener.getSessionStateListener();
    }
	
    public void setSessionStateListener(
            SessionStateListener sessionStateListener) {
        this.sessionStateListener.setSessionStateListener(sessionStateListener);
    }
    
    public MessageReceiverListener getMessageReceiverListener() {
        return messageReceiverListener;
    }
    
	public void setMessageReceiverListener(
			MessageReceiverListener messageReceiverListener) {
		this.messageReceiverListener = messageReceiverListener;
	}
	
    /**
     * This method provided for monitoring need.
     * 
     * @return the last activity timestamp.
     */
    public long getLastActivityTimestamp() {
        return sessionContext.getLastActivityTimestamp();
    }

    public void unbindAndClose() {
    	try {
    		unbind();
    	} catch (ResponseTimeoutException e) {
    		logger.error("Timeout waiting unbind response", e);
    	} catch (InvalidResponseException e) {
    		logger.error("Receive invalid unbind response", e);
    	} catch (IOException e) {
    		logger.error("IO error found ", e);
    	}
    	close();
    }

    public void close() {
		sessionContext.close();
		try {
            conn.close();
        } catch (IOException e) {
        }
	}
	
	private synchronized boolean isReadPdu() {
		return sessionContext.getSessionState().isBound() || sessionContext.getSessionState().equals(SessionState.OPEN);
	}
	
	@Override
	protected void finalize() throws Throwable {
		close();
	}
	
	private void fireAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
		if (messageReceiverListener != null) {
			messageReceiverListener.onAcceptDeliverSm(deliverSm);
        } else { 
			logger.warn("Receive deliver_sm but MessageReceiverListener is null. Short message = " + new String(deliverSm.getShortMessage()));
        }
	}
	
    private synchronized static final String newSessionId() {
        return IntUtil.toHexString(random.nextInt());
    }
    
	private class ResponseHandlerImpl implements ResponseHandler {
		
		public void processDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
			fireAcceptDeliverSm(deliverSm);
		}
		
		@SuppressWarnings("unchecked")
		public PendingResponse<Command> removeSentItem(int sequenceNumber) {
			return (PendingResponse<Command>)pendingResponse.remove(sequenceNumber);
		}
		
		public void notifyUnbonded() {
		    sessionContext.unbound();
		}
		
		public void sendDeliverSmResp(int sequenceNumber) throws IOException {
			try {
				pduSender.sendDeliverSmResp(out, sequenceNumber);
				logger.debug("deliver_sm_resp with seq_number " + sequenceNumber + " has been sent");
			} catch (PDUStringException e) {
				logger.error("Failed sending deliver_sm_resp", e);
			}
		}
		
		public void sendEnquireLinkResp(int sequenceNumber) throws IOException {
		    logger.debug("Sending enquire_link_resp");
			pduSender.sendEnquireLinkResp(out, sequenceNumber);
		}
		
		public void sendGenerickNack(int commandStatus, int sequenceNumber) throws IOException {
			pduSender.sendGenericNack(out, commandStatus, sequenceNumber);
		}
		
		public void sendNegativeResponse(int originalCommandId, int commandStatus, int sequenceNumber) throws IOException {
			pduSender.sendHeader(out, originalCommandId | SMPPConstant.MASK_CID_RESP, commandStatus, sequenceNumber);
		}
		
		public void sendUnbindResp(int sequenceNumber) throws IOException {
			pduSender.sendUnbindResp(out, SMPPConstant.STAT_ESME_ROK, sequenceNumber);
		}
	}
	
	/**
	 * Worker to read the PDU.
	 * 
	 * @author uudashr
	 *
	 */
	private class PDUReaderWorker extends Thread {
	    private ExecutorService executorService = Executors.newFixedThreadPool(pduDispatcherThreadCount);
		
	    private Runnable onIOExceptionTask = new Runnable() {
		    public void run() {
		        close();
		    };
		};
		
	    @Override
		public void run() {
			logger.info("Starting PDUReaderWorker");
			while (isReadPdu()) {
                readPDU();
			}
			executorService.shutdownNow();
			close();
			logger.info("PDUReaderWorker stop");
		}
		
		private void readPDU() {
	        try {
	            Command pduHeader = null;
	            byte[] pdu = null;
	            
                pduHeader = pduReader.readPDUHeader(in);
                pdu = pduReader.readPDU(in, pduHeader);
	            
                /*
                 * When the processing PDU is need user interaction via event,
                 * the code on event might take non-short time, so we need to
                 * process it concurrently.
                 */
                PDUProcessTask task = new PDUProcessTask(pduHeader, pdu,
                        sessionContext.getStateProcessor(), responseHandler,
                        sessionContext, onIOExceptionTask);
	            executorService.execute(task);
	            
	        } catch (InvalidCommandLengthException e) {
	            logger.warn("Receive invalid command length", e);
	            try {
	                pduSender.sendGenericNack(out, SMPPConstant.STAT_ESME_RINVCMDLEN, 0);
	            } catch (IOException ee) {
	                logger.warn("Failed sending generic nack", ee);
	            }
	            unbindAndClose();
	        } catch (SocketTimeoutException e) {
	            notifyNoActivity();
	        } catch (IOException e) {
	            close();
	        }
	    }
		
	    /**
	     * Notify for no activity.
	     */
	    private void notifyNoActivity() {
	        logger.debug("No activity notified");
	        enquireLinkSender.enquireLink();
	    }
	}
	
	
	/**
	 * FIXME uud: we can create general class for SMPPServerSession and SMPPSession
	 * @author uudashr
	 *
	 */
	private class EnquireLinkSender extends Thread {
        private final AtomicBoolean sendingEnquireLink = new AtomicBoolean(false);
        
        @Override
        public void run() {
            logger.info("Starting EnquireLinkSender");
            while (isReadPdu()) {
                while (!sendingEnquireLink.compareAndSet(true, false) && isReadPdu()) {
                    synchronized (sendingEnquireLink) {
                        try {
                            sendingEnquireLink.wait(500);
                        } catch (InterruptedException e) {
                        }
                    }
                }
                if (!isReadPdu()) {
                    break;
                }
                try {
                    sendEnquireLink();
                } catch (ResponseTimeoutException e) {
                    close();
                } catch (InvalidResponseException e) {
                    // lets unbind gracefully
                    unbindAndClose();
                } catch (IOException e) {
                    close();
                }
            }
            logger.info("EnquireLinkSender stop");
        }
        
        /**
         * This method will send enquire link asynchronously.
         */
        public void enquireLink() {
            if (sendingEnquireLink.compareAndSet(false, true)) {
                logger.debug("Sending enquire link notify");
                synchronized (sendingEnquireLink) {
                    sendingEnquireLink.notify();
                }
            } else {
                logger.debug("Not sending enquire link notify");
            }
        }
        
        /**
         * Ensure we have proper link.
         * 
         * @throws ResponseTimeoutException if there is no valid response after defined millisecond.
         * @throws InvalidResponseException if there is invalid response found.
         * @throws IOException if there is an IO error found.
         */
        private void sendEnquireLink() throws ResponseTimeoutException, InvalidResponseException, IOException {
            int seqNum = sequence.nextValue();
            PendingResponse<EnquireLinkResp> pendingResp = new PendingResponse<EnquireLinkResp>(transactionTimer);
            pendingResponse.put(seqNum, pendingResp);
            
            try {
                logger.debug("Sending enquire_link");
                pduSender.sendEnquireLink(out, seqNum);
            } catch (IOException e) {
                logger.error("Failed sending enquire link", e);
                pendingResponse.remove(seqNum);
                throw e;
            }
            
            try {
                pendingResp.waitDone();
                logger.debug("Enquire link response received");
            } catch (ResponseTimeoutException e) {
                pendingResponse.remove(seqNum);
                throw e;
            } catch (InvalidResponseException e) {
                pendingResponse.remove(seqNum);
                throw e;
            }
            
            if (pendingResp.getResponse().getCommandStatus() != SMPPConstant.STAT_ESME_ROK) {
                // this is ok, we just want to enquire we have proper link.
                logger.warn("Receive NON-OK response of enquire link: " + pendingResp.getResponse().getCommandIdAsHex());
            }
        }
    }
	
	/**
	 * Session state listener for internal class use.
	 * 
	 * @author uudashr
	 *
	 */
	private class SessionStateListenerDecorator implements SessionStateListener {
	    private SessionStateListener sessionStateListener;
	    
	    public void onStateChange(SessionState newState, SessionState oldState,
	            Object source) {
	        /*
	         * We need to set SO_TIMEOUT to sessionTimer so when timeout occur, 
	         * a SocketTimeoutException will be raised. When Exception raised we
	         * can send an enquireLinkCommand.
	         */
	        if (newState.isBound()) {
	            try {
                    conn.setSoTimeout(sessionTimer);
                } catch (IOException e) {
                    logger.error("Failed setting so_timeout for session timer", e);
                }
	        }
	        if (sessionStateListener != null) {
	            sessionStateListener.onStateChange(newState, oldState, source);
	        }
	    }
	    
	    public void setSessionStateListener(
                SessionStateListener sessionStateListener) {
            this.sessionStateListener = sessionStateListener;
        }
	    
	    public SessionStateListener getSessionStateListener() {
            return sessionStateListener;
        }
	}
}