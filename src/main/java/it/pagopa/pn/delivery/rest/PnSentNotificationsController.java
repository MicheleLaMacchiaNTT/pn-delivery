package it.pagopa.pn.delivery.rest;

import it.pagopa.pn.commons.log.PnAuditLogBuilder;
import it.pagopa.pn.commons.log.PnAuditLogEvent;
import it.pagopa.pn.commons.log.PnAuditLogEventType;
import it.pagopa.pn.delivery.exception.PnDeliveryExceptionCodes;
import it.pagopa.pn.delivery.exception.PnInvalidInputException;
import it.pagopa.pn.delivery.exception.PnNotificationNotFoundException;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.SenderReadB2BApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.SenderReadWebApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.*;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.svc.NotificationAttachmentService;
import it.pagopa.pn.delivery.svc.search.NotificationRetrieverService;
import it.pagopa.pn.delivery.utils.ModelMapperFactory;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Base64Utils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static it.pagopa.pn.commons.exceptions.PnExceptionsCodes.ERROR_CODE_PN_GENERIC_INVALIDPARAMETER_REQUIRED;

@RestController
@Slf4j
public class PnSentNotificationsController implements SenderReadB2BApi,SenderReadWebApi {

    private final NotificationRetrieverService retrieveSvc;
    private final NotificationAttachmentService notificationAttachmentService;
    private final ModelMapperFactory modelMapperFactory;

    public PnSentNotificationsController(NotificationRetrieverService retrieveSvc, NotificationAttachmentService notificationAttachmentService, ModelMapperFactory modelMapperFactory) {
        this.retrieveSvc = retrieveSvc;
        this.notificationAttachmentService = notificationAttachmentService;
        this.modelMapperFactory = modelMapperFactory;
    }

    @Override
    public ResponseEntity<FullSentNotification> getSentNotification(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, String iun, List<String> xPagopaPnCxGroups) {
        InternalNotification internalNotification = retrieveSvc.getNotificationInformation( iun, true, true, xPagopaPnCxId);
        if ( NotificationStatus.IN_VALIDATION.equals( internalNotification.getNotificationStatus() ) ) {
            log.info( "Unable to find notification with iun={} cause status={}", internalNotification.getIun(), internalNotification.getNotificationStatus() );
            throw new PnNotificationNotFoundException( "Unable to find notification with iun="+ internalNotification.getIun() );
        }
        ModelMapper mapper = modelMapperFactory.createModelMapper( InternalNotification.class, FullSentNotification.class );
        FullSentNotification result = mapper.map( internalNotification, FullSentNotification.class );
        return ResponseEntity.ok( result );
    }



    @Override
    public ResponseEntity<NotificationSearchResponse> searchSentNotification(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, OffsetDateTime startDate, OffsetDateTime endDate, List<String> xPagopaPnCxGroups, String recipientId, NotificationStatus status, String subjectRegExp, String iunMatch, Integer size, String nextPagesKey) {
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_SEARCH_SND, "searchSentNotification")
                .cxId(xPagopaPnCxId)
                .cxType(xPagopaPnCxType.toString())
                .iun(iunMatch)
                .uid(xPagopaPnUid)
                .build();
        logEvent.log();
        InputSearchNotificationDto searchDto = new InputSearchNotificationDto.Builder()
                .bySender(true)
                .senderReceiverId(xPagopaPnCxId)
                .startDate(startDate.toInstant())
                .endDate(endDate.toInstant())
                .filterId(recipientId)
                .statuses(status==null?List.of():List.of(status))
                .receiverIdIsOpaque(false)
                .groups( xPagopaPnCxGroups )
                .subjectRegExp(subjectRegExp)
                .iunMatch(iunMatch)
                .size(size)
                .nextPagesKey(nextPagesKey)
                .build();
        ResultPaginationDto<NotificationSearchRow,String> serviceResult;
        NotificationSearchResponse response = new NotificationSearchResponse();
        try {
            serviceResult =  retrieveSvc.searchNotification( searchDto );
            ModelMapper mapper = modelMapperFactory.createModelMapper(ResultPaginationDto.class, NotificationSearchResponse.class );
            response = mapper.map( serviceResult, NotificationSearchResponse.class );
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure(exc.getMessage()).log();
            throw exc;
        }
        return ResponseEntity.ok( response );
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return SenderReadB2BApi.super.getRequest();
    }

    @Override
    public ResponseEntity<NewNotificationRequestStatusResponse> getNotificationRequestStatus(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String notificationRequestId, String paProtocolNumber, String idempotenceToken) {
        InternalNotification internalNotification;
        if (StringUtils.hasText( notificationRequestId )) {
            String iun = new String(Base64Utils.decodeFromString(notificationRequestId), StandardCharsets.UTF_8);
            internalNotification = retrieveSvc.getNotificationInformation( iun, true, true );
        } else {
            if ( !StringUtils.hasText( paProtocolNumber ) ) {
                throw new PnInvalidInputException(ERROR_CODE_PN_GENERIC_INVALIDPARAMETER_REQUIRED, "paProtocolNumber" );
            }
            if (!StringUtils.hasText( idempotenceToken ) ) {
                throw new PnInvalidInputException(ERROR_CODE_PN_GENERIC_INVALIDPARAMETER_REQUIRED, "idempotenceToken" );
            }
            internalNotification = retrieveSvc.getNotificationInformation( xPagopaPnCxId, paProtocolNumber, idempotenceToken);
        }

        ModelMapper mapper = modelMapperFactory.createModelMapper(
                InternalNotification.class,
                NewNotificationRequestStatusResponse.class
        );
        NewNotificationRequestStatusResponse response = mapper.map(
                internalNotification,
                NewNotificationRequestStatusResponse.class
        );
        response.setNotificationRequestId( Base64Utils.encodeToString( internalNotification.getIun().getBytes(StandardCharsets.UTF_8) ));

        NotificationStatus lastStatus;
        if ( internalNotification.getNotificationStatusHistory() != null
                &&  !internalNotification.getNotificationStatusHistory().isEmpty()  ) {
            lastStatus = internalNotification.getNotificationStatusHistory().get(
                    internalNotification.getNotificationStatusHistory().size() - 1 ).getStatus();
        } else {
            log.debug( "No status history for notificationRequestId={}", notificationRequestId );
            lastStatus = NotificationStatus.IN_VALIDATION;
        }

        switch ( lastStatus ) {
            case IN_VALIDATION: {
                response.setNotificationRequestStatus( "WAITING" );
                response.retryAfter( 10 );
                response.setIun( null );
                break;
            }
            case REFUSED: {
                response.setNotificationRequestStatus( "REFUSED" );
                response.setIun( null );
                Optional<TimelineElement> timelineElement = internalNotification.getTimeline().stream().filter(
                        tle -> TimelineElementCategory.REQUEST_REFUSED.equals( tle.getCategory() ) ).findFirst();
                setRefusedErrors( response, timelineElement );
                break; }
            default: response.setNotificationRequestStatus( "ACCEPTED" );
        }
        return ResponseEntity.ok( response );
    }

    private void setRefusedErrors(NewNotificationRequestStatusResponse response, Optional<TimelineElement> timelineElement) {
        if (timelineElement.isPresent() ) {
            List<String> errors = timelineElement.get().getDetails().getErrors();
            List<ProblemError> problemErrorList = errors.stream().map(
                    error -> ProblemError.builder()
                    .detail( error )
                    .build()
            ).collect(Collectors.toList());
            response.setErrors( problemErrorList );
        }
    }

    @Override
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getSentNotificationAttachment(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, String iun, Integer recipientIdx, String attachmentName, List<String> xPagopaPnCxGroups) {
        NotificationAttachmentDownloadMetadataResponse response = new NotificationAttachmentDownloadMetadataResponse();
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_ATCHOPEN_SND, "getSentNotificationAttachment={}", attachmentName)
                .uid(xPagopaPnUid)
                .iun(iun)
                .cxId(xPagopaPnCxId)
                .cxType(xPagopaPnCxType.toString())
                .build();
        logEvent.log();
        try {
            response = notificationAttachmentService.downloadAttachmentWithRedirect( iun,
                    xPagopaPnCxType.toString(),
                    xPagopaPnCxId,
                    null,
                    recipientIdx,
                    attachmentName,
                    false
            );
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure(exc.getMessage()).log();
            throw exc;
        }
     
        return ResponseEntity.ok( response );
    }

    @Override
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getSentNotificationDocument(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, String iun, Integer docIdx, List<String> xPagopaPnCxGroups) {
        NotificationAttachmentDownloadMetadataResponse response = new NotificationAttachmentDownloadMetadataResponse();
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_DOCOPEN_SND, "getSentNotificationDocument={}", docIdx)
                .uid(xPagopaPnUid)
                .iun(iun)
                .cxId(xPagopaPnCxId)
                .cxType(xPagopaPnCxType.toString())
                .build();
        logEvent.log();
        try {
            response = notificationAttachmentService.downloadDocumentWithRedirect(
                    iun,
                    xPagopaPnCxType.toString(),
                    xPagopaPnCxId,
                    null,
                    docIdx,
                    false
            );
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure(exc.getMessage()).log();
            throw exc;
        }
        return ResponseEntity.ok( response );
    }
}
