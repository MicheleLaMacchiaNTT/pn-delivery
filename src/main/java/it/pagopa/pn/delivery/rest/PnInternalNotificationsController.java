package it.pagopa.pn.delivery.rest;

import it.pagopa.pn.commons.log.PnAuditLogBuilder;
import it.pagopa.pn.commons.log.PnAuditLogEvent;
import it.pagopa.pn.commons.log.PnAuditLogEventType;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.InternalOnlyApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.*;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.svc.NotificationAttachmentService;
import it.pagopa.pn.delivery.svc.NotificationPriceService;
import it.pagopa.pn.delivery.svc.NotificationQRService;
import it.pagopa.pn.delivery.svc.StatusService;
import it.pagopa.pn.delivery.svc.search.NotificationRetrieverService;
import it.pagopa.pn.delivery.utils.ModelMapperFactory;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@RestController
public class PnInternalNotificationsController implements InternalOnlyApi {

    private final NotificationRetrieverService retrieveSvc;
    private final StatusService statusService;
    private final NotificationPriceService priceService;
    private final NotificationQRService qrService;
    private final NotificationAttachmentService notificationAttachmentService;

    private final ModelMapperFactory modelMapperFactory;

    public PnInternalNotificationsController(NotificationRetrieverService retrieveSvc, StatusService statusService, NotificationPriceService priceService, NotificationQRService qrService, NotificationAttachmentService notificationAttachmentService, ModelMapperFactory modelMapperFactory) {
        this.retrieveSvc = retrieveSvc;
        this.statusService = statusService;
        this.priceService = priceService;
        this.qrService = qrService;
        this.notificationAttachmentService = notificationAttachmentService;
        this.modelMapperFactory = modelMapperFactory;
    }

    @Override
    public ResponseEntity<NotificationCostResponse> getNotificationCostPrivate(String paTaxId, String noticeCode) {
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_REQCOST, "getNotificationCostPrivate paTaxId={} noticeCode={}", paTaxId, noticeCode)
                .mdcEntry("paTaxId",paTaxId)
                .mdcEntry("noticeCode", noticeCode)
                .build();
        logEvent.log();
        NotificationCostResponse response;
        try {
            response = priceService.getNotificationCost( paTaxId, noticeCode );
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure("Exception on get notification cost private= " + exc.getMessage()).log();
            throw exc;
        }
        return ResponseEntity.ok( response );
    }

    @Override
    public ResponseEntity<ResponseCheckAarDto> checkAarQrCode(RequestCheckAarDto requestCheckAarDto) {
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        String aarQrCodeValue = requestCheckAarDto.getAarQrCodeValue();
        String recipientType = requestCheckAarDto.getRecipientType();
        String recipientInternalId = requestCheckAarDto.getRecipientInternalId();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_REQQR, "getNotificationQrPrivate aarQrCodeValue={} recipientType={} recipientInternalId={}",
                        aarQrCodeValue,
                        recipientType,
                        recipientInternalId)
                .mdcEntry("aarQrCodeValue", aarQrCodeValue)
                .mdcEntry("recipientType", recipientType)
                .mdcEntry("recipientInternalId", recipientInternalId)
                .build();
        logEvent.log();
        ResponseCheckAarDto responseCheckAarDto;
        try {
            responseCheckAarDto = qrService.getNotificationByQR( requestCheckAarDto );
        } catch (Exception exc) {
            logEvent.generateFailure("Exception on get notification qr private= " + exc.getMessage()).log();
            throw exc;
        }
        return ResponseEntity.ok( responseCheckAarDto );
    }

    @Override
    public ResponseEntity<SentNotification> getSentNotificationPrivate(String iun) {
        InternalNotification notification = retrieveSvc.getNotificationInformation(iun, false, true);
        ModelMapper mapper = modelMapperFactory.createModelMapper(InternalNotification.class, SentNotification.class);
        SentNotification sentNotification = mapper.map(notification, SentNotification.class);

        int recIdx = 0;
        for (NotificationRecipient rec : sentNotification.getRecipients()) {
            rec.setInternalId(notification.getRecipientIds().get(recIdx));
            recIdx += 1;
        }

        return ResponseEntity.ok(sentNotification);
    }

    @Override
    public ResponseEntity<Void> updateStatus(RequestUpdateStatusDto requestUpdateStatusDto) {
        String logMessage = String.format(
                "Update status for iun=%s nextStatus=%s", requestUpdateStatusDto.getIun(), requestUpdateStatusDto.getNextStatus()
        );
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_STATUS, logMessage)
                .iun(requestUpdateStatusDto.getIun())
                .build();
        logEvent.log();
        try {
            statusService.updateStatus(requestUpdateStatusDto);
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure(logMessage).log();
            throw exc;
        }
        return ResponseEntity.ok().build();
    }

    @Override
    public  ResponseEntity<NotificationSearchResponse> searchNotificationsPrivate(OffsetDateTime startDate, OffsetDateTime endDate,
                                                                                  String recipientId, Boolean recipientIdOpaque,
                                                                                  String senderId, List<NotificationStatus> status,
                                                                                  Integer size, String nextPagesKey) {

        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_SEARCH_SND, "searchNotificationsPrivate")
                .build();
        logEvent.log();
        if (StringUtils.hasText( recipientId ) && StringUtils.hasText( senderId )) {
            throw new IllegalArgumentException( "Please specify alternatively recipientId or senderId search params" );
        }
        InputSearchNotificationDto searchDto = new InputSearchNotificationDto.Builder()
                .bySender( StringUtils.hasText( senderId ) )
                .senderReceiverId( StringUtils.hasText( recipientId )? recipientId : senderId)
                .startDate(startDate.toInstant())
                .endDate(endDate.toInstant())
                .statuses(status==null?List.of():status)
                .receiverIdIsOpaque(recipientIdOpaque)
                .size(size)
                .maxPageNumber( 1 )
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
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getReceivedNotificationAttachmentPrivate(String iun, String attachmentName, String recipientInternalId, String mandateId) {
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        NotificationAttachmentDownloadMetadataResponse response = new NotificationAttachmentDownloadMetadataResponse();
        PnAuditLogEvent logEvent = auditLogBuilder.before(PnAuditLogEventType.AUD_NT_ATCHOPEN_RCP, "getReceivedNotificationAttachmentPrivate={}", attachmentName)
                .iun(iun)
                .cxId(recipientInternalId)
                .cxType("PF")
                .build();
        logEvent.log();
        try {
            response = notificationAttachmentService.downloadAttachmentWithRedirect(
                    iun,
                    "PF",
                    recipientInternalId,
                    mandateId,
                    null,
                    attachmentName,
                    false
            );
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure(exc.getMessage()).log();
            throw exc;
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getReceivedNotificationDocumentPrivate(String iun, Integer docIdx, String recipientInternalId, String mandateId) {
        PnAuditLogBuilder auditLogBuilder = new PnAuditLogBuilder();
        NotificationAttachmentDownloadMetadataResponse response = new NotificationAttachmentDownloadMetadataResponse();
        PnAuditLogEvent logEvent = auditLogBuilder
                .before(PnAuditLogEventType.AUD_NT_DOCOPEN_RCP, "getReceivedNotificationDocumentPrivate {}", docIdx)
                .iun(iun)
                .cxId(recipientInternalId)
                .build();
        logEvent.log();
        try {
            response = notificationAttachmentService.downloadDocumentWithRedirect(
                    iun,
                    "PF",
                    recipientInternalId,
                    mandateId,
                    docIdx,
                    false
            );
            logEvent.generateSuccess().log();
        } catch (Exception exc) {
            logEvent.generateFailure(exc.getMessage()).log();
            throw exc;
        }
        return ResponseEntity.ok(response);
    }

}
