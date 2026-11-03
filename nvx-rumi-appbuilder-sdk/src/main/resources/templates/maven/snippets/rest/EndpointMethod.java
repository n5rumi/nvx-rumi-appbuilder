    // @rest-endpoint {{HttpMethod}} {{EndpointPath}}
    @{{HttpMethod}}
    @Produces(MediaType.APPLICATION_JSON)
    public Response {{JavaMethodName}}({{Parameters}}) {
{{Body}}
    }
    // @rest-endpoint-end
