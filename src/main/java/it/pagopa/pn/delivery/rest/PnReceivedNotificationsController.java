package it.pagopa.pn.delivery.rest;

import it.pagopa.pn.api.rest.PnDeliveryRestConstants;
import it.pagopa.pn.commons.exceptions.PnValidationException;
import it.pagopa.pn.delivery.PnDeliveryConfigs;
import it.pagopa.pn.delivery.exception.PnNotFoundException;
import it.pagopa.pn.delivery.generated.openapi.server.v1.api.RecipientReadApi;
import it.pagopa.pn.delivery.generated.openapi.server.v1.dto.*;
import it.pagopa.pn.delivery.models.InputSearchNotificationDto;
import it.pagopa.pn.delivery.models.InternalNotification;
import it.pagopa.pn.delivery.models.ResultPaginationDto;
import it.pagopa.pn.delivery.rest.dto.ResErrorDto;
import it.pagopa.pn.delivery.rest.utils.HandleNotFound;
import it.pagopa.pn.delivery.rest.utils.HandleValidation;
import it.pagopa.pn.delivery.svc.search.NotificationRetrieverService;
import it.pagopa.pn.delivery.utils.ModelMapperFactory;
import org.modelmapper.ModelMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@RestController
public class PnReceivedNotificationsController implements RecipientReadApi {
    private final NotificationRetrieverService retrieveSvc;
    private final PnDeliveryConfigs cfg;
    private final ModelMapperFactory modelMapperFactory;
    public static final String VALIDATION_ERROR_STATUS = "Validation error";
    public static final String NOT_FOUND_ERROR_STATUS = "Not Found Error";

    public PnReceivedNotificationsController(NotificationRetrieverService retrieveSvc, PnDeliveryConfigs cfg, ModelMapperFactory modelMapperFactory) {
        this.retrieveSvc = retrieveSvc;
        this.cfg = cfg;
        this.modelMapperFactory = modelMapperFactory;
    }


    @Override
    public ResponseEntity<NotificationSearchResponse> searchReceivedNotification(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, Date startDate, Date endDate, String mandateId, String senderId, NotificationStatus status, String subjectRegExp, String iunMatch, Integer size, String nextPagesKey) {
        InputSearchNotificationDto searchDto = new InputSearchNotificationDto.Builder()
                .bySender(false)
                .senderReceiverId(xPagopaPnCxId)
                .startDate(startDate.toInstant())
                .endDate(endDate.toInstant())
                .mandateId(mandateId)
                .filterId(senderId)
                .status(status)
                //.groups( groups != null ? Arrays.asList( groups ) : null )
                .subjectRegExp(subjectRegExp)
                .iunMatch(iunMatch)
                .size(size)
                .nextPagesKey(nextPagesKey)
                .build();

        ResultPaginationDto<NotificationSearchRow,String> serviceResult =  retrieveSvc.searchNotification( searchDto );

        ModelMapper mapper = modelMapperFactory.createModelMapper(ResultPaginationDto.class, NotificationSearchResponse.class );
        NotificationSearchResponse response = mapper.map( serviceResult, NotificationSearchResponse.class );
        return ResponseEntity.ok( response );
    }

    @Override
    public ResponseEntity<FullReceivedNotification> getReceivedNotification(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String iun) {
        InternalNotification internalNotification =  retrieveSvc.getNotificationAndNotifyViewedEvent( iun, xPagopaPnCxId );

        ModelMapper mapper = modelMapperFactory.createModelMapper( InternalNotification.class, FullReceivedNotification.class );

        FullReceivedNotification result = mapper.map( internalNotification, FullReceivedNotification.class );
        return ResponseEntity.ok( result );
    }


    @GetMapping( PnDeliveryRestConstants.NOTIFICATION_VIEWED_PATH )
    public ResponseEntity<Resource> getReceivedNotificationDocument(
            @RequestHeader(name = PnDeliveryRestConstants.CX_ID_HEADER) String userId,
            @PathVariable("iun") String iun,
            @PathVariable("documentIndex") int documentIndex,
            ServerHttpResponse response
    ) {
        if(cfg.isDownloadWithPresignedUrl()){
            String redirectUrl = retrieveSvc.downloadDocumentWithRedirect(iun, documentIndex);
            //response.setStatusCode(HttpStatus.OK);
            //response.getHeaders().setLocation(URI.create( redirectUrl ));

            response.getHeaders().setContentType( MediaType.APPLICATION_JSON );
            String responseString  = "{ \"url\": \"" + redirectUrl + "\"}";
            Resource resource = new ByteArrayResource( responseString.getBytes(StandardCharsets.UTF_8) );
            return ResponseEntity.ok( resource );
        }else {
            ResponseEntity<Resource> resource = retrieveSvc.downloadDocument(iun, documentIndex);
            return AttachmentRestUtils.prepareAttachment( resource, iun, "doc" + documentIndex );
        }
    }

    @Override
    public ResponseEntity<NotificationAttachmentDownloadMetadataResponse> getReceivedNotificationDocument(String xPagopaPnUid, CxTypeAuthFleet xPagopaPnCxType, String xPagopaPnCxId, List<String> xPagopaPnCxGroups, String iun, BigDecimal docIdx) {
        NotificationAttachmentDownloadMetadataResponse.NotificationAttachmentDownloadMetadataResponseBuilder responseBuilder = NotificationAttachmentDownloadMetadataResponse.builder();

        String redirectUrl = retrieveSvc.downloadDocumentWithRedirect(iun, docIdx.intValue());
        // TODO info mancanti di NotificationAttachmentDonwnloadMetadataResponse quando get file da safe-storage
        NotificationAttachmentDownloadMetadataResponse response = responseBuilder.url(redirectUrl).build();
        return ResponseEntity.ok( response );

    }

    @ExceptionHandler({PnValidationException.class})
    public ResponseEntity<ResErrorDto> handleValidationException(PnValidationException ex){
        return HandleValidation.handleValidationException(ex, VALIDATION_ERROR_STATUS);
    }

    @ExceptionHandler({PnNotFoundException.class})
    public ResponseEntity<ResErrorDto> handleNotFoundException(PnNotFoundException ex) {
        return HandleNotFound.handleNotFoundException( ex, NOT_FOUND_ERROR_STATUS );
    }
}
