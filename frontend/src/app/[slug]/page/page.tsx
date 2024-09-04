"use client"

import { Icons } from "@/components/icons";
import { Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import withAuth from '@/hoc/withAuth';
import React, { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { useForm } from "react-hook-form";
import { ExternalLink } from "lucide-react";
import Loading from "@/components/Loading";
import { useRouter } from "next/navigation";
import Image from 'next/image';

import { Avatar } from '@/components/ui/avatar';
import FileUploadButton from "@/components/FileButton";


const Dashboard: React.FC = ({ params }) => {
  const { toast } = useToast()

  const [data, setData] = useState(null)
  const [isLoading, setLoading] = useState(true)
  const [defaultValues, setDefaultValues] = useState({})
  const [uploadingLogo, setUploadingLogo] = useState(false)
  const [uploadedLogoUrl, setUploadedLogoUrl] = useState('')

  const [uploadingFavicon, setUploadingFavicon] = useState(false)
  const [uploadedFaviconUrl, setUploadedFaviconUrl] = useState('')

  const form = useForm({ defaultValues })
  const { reset } = form; // Get reset function from useForm

  const router = useRouter()

  useEffect(() => {
    fetch(`/api/auth/pages/fetch-org`, {
      headers: {
        "x-org-slug": params.slug
      }
    })
      .then((res) => res.json())
      .then((data) => {
        setData(data)
        reset(data)
        setLoading(false)
      })

  }, [params.id, params.slug, reset])

  async function onSubmit(data) {
    setLoading(true)
    try {

      const resp = await fetch(`/api/auth/pages/edit-org`, {
        method: 'POST',
        headers: {
          "x-org-slug": params.slug,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(data)
      });
      const respData = await resp.json();
      if (resp.ok) {
        setData(respData)
        reset(respData)
        toast({
          title: 'Updated successfully',
        })
        if (data.slug !== params.slug) {
          router.push(`/${data.slug}/page`)
        }
      } else {
        toast({
          title: 'Failed to save',
          description: `${respData.message}`,
          variant: 'destructive'
        })
      }
      setLoading(false)
    } catch (err) {
      console.log(err)
      toast({
        title: 'Something went wrong',
        description: 'Please try again by reloading the page, if the problem persists contact support',
        variant: 'destructive'
      })
    }
    setTimeout(() => { setLoading(false) }, 1000)
  }

  if (!data) return <Loading />


  return (
    <main className="flex flex-1 flex-col gap-4 p-4 lg:gap-6 lg:p-6 h-full">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold md:text-2xl">Page - {data && data.name}</h1>
      </div>
      <div
        className="flex flex-1 justify-center rounded-lg border border-dashed shadow-sm"
      >
        <div className="w-full p-4">
          <div className="space-y-6 mb-8">
            <div className="flex items-center space-x-4">
              <div className="">
                <img
                  src={uploadedFaviconUrl || data.favicon}
                  alt="Favicon"
                  width={48}
                  height={48}
                  className="object-contain"
                />
              </div>
              <div>
                <FileUploadButton
                  uploading={uploadingFavicon}
                  setUploading={setUploadingFavicon}
                  setUploadedFileUrl={setUploadedFaviconUrl}
                  uploadedFileUrl={uploadedFaviconUrl}
                />
              </div>
            </div>
            <div className="flex items-center space-x-4">
              <div className="w-12 h-12">
                <img
                  src={uploadedLogoUrl || data.logo}
                  alt="Logo"
                  width={128}
                  height={128}
                  className="object-contain w-full h-full"
                />
              </div>
              <div>
                <FileUploadButton
                  uploading={uploadingLogo}
                  setUploading={setUploadingLogo}
                  setUploadedFileUrl={setUploadedLogoUrl}
                  uploadedFileUrl={uploadedLogoUrl}
                />
              </div>
            </div>
          </div>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-8">
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Title</FormLabel>
                    <FormControl>
                      <Input disabled={isLoading} placeholder="name" {...field} />
                    </FormControl>
                    <FormDescription>
                      This is the page title.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="slug"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Slug</FormLabel>
                    <FormControl>
                      <Input disabled={isLoading} placeholder="slug" {...field} />
                    </FormControl>
                    <FormDescription>
                      <p>
                        This is the org slug. It should be unique and can only contain letters, numbers, and hyphens.
                      </p>
                      <p>
                        Your page will be accessible at <a href={`https://${field.value}.suggestfeature.com`} target="_blank"
                          className="inline-block hover:text-indigo-700">
                          https://{field.value}.suggestfeature.com<ExternalLink className="ml-1 h-4 w-4 inline-block" />
                        </a>
                      </p>
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="customDomain"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Custom Domain</FormLabel>
                    <FormControl>
                      <Input disabled={isLoading} placeholder="feature-request.yourdomain.com" {...field} />
                    </FormControl>
                    <FormDescription>
                      This is the custom domain for the page. You have to setup a CNAME mapping in your DNS server to our domain cname.suggestfeature.com before setting it here.
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <Button type="submit" disabled={isLoading || uploadingLogo || uploadingFavicon}>
                {(isLoading || uploadingFavicon || uploadingLogo) &&
                  <Icons.spinner className="mr-2 h-4 w-4 animate-spin" />
                }
                {
                  (uploadingLogo || uploadingFavicon) ? 'Uploading...' : (isLoading ? 'Saving...' : 'Save')
                }
              </Button>
            </form>
          </Form>
        </div>
      </div>
    </main>
  )
}
export default withAuth(Dashboard);

